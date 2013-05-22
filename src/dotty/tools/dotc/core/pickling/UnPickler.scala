package dotty.tools
package dotc
package core
package pickling

import java.io.IOException
import java.lang.Float.intBitsToFloat
import java.lang.Double.longBitsToDouble

import Contexts._, Symbols._, Types._, Scopes._, SymDenotations._, Names._, NameOps._
import StdNames._, Denotations._, NameOps._, Flags._, Constants._, Annotations._
import util.Positions._
import ast.Trees, ast.tpd._
import printing.Texts._
import printing.Printer
import io.AbstractFile
import scala.reflect.internal.pickling.PickleFormat._
import Decorators._
import scala.collection.{ mutable, immutable }
import scala.collection.mutable.ListBuffer
import scala.annotation.switch

object UnPickler {

  /** Exception thrown if classfile is corrupted */
  class BadSignature(msg: String) extends RuntimeException(msg)

  case class TempPolyType(tparams: List[Symbol], tpe: Type) extends UncachedGroundType {
    override def fallbackToText(printer: Printer): Text =
      "[" ~ printer.dclsText(tparams, ", ") ~ "]" ~ printer.toText(tpe)
  }

  /** Temporary type for classinfos, will be decomposed on completion of the class */
  case class TempClassInfoType(parentTypes: List[Type], decls: Scope, clazz: Symbol) extends UncachedGroundType

  /** Convert temp poly type to some native Dotty idiom.
   *  @param forSym  The symbol that gets the converted type as info.
   *  If `forSym` is not an abstract type, this simply returns an equivalent `PolyType`.
   *  If `forSym` is an abstract type, it converts a
   *
   *      TempPolyType(List(v_1 T_1, ..., v_n T_n), lo .. hi)
   *
   *  to
   *
   *      lo .. HigherKinded_n[v_1Between[lo_1, hi_1],..., v_nBetween[lo_n, hi_n]] & hi
   *
   *  where lo_i, hi_i are the lower/upper bounds of the type parameters T_i.
   *  This works only as long as none of the type parameters T_i appears in any
   *  of the bounds or the result type. Such occurrences of type parameters are
   *  replaced by Any, and a warning is issued in this case.
   */
  def depoly(tp: Type, forSym: SymDenotation)(implicit ctx: Context): Type = tp match {
    case TempPolyType(tparams, restpe) =>
      if (forSym.isAbstractType) {
        val typeArgs = for (tparam <- tparams) yield {
          val TypeBounds(lo, hi) = tparam.info
          defn.hkBoundsClass(tparam.variance).typeConstructor
            .appliedTo(List(lo, hi))
        }
        val elimTparams: Type => Type = _.subst(tparams, tparams map (_ => defn.AnyType))
        val correctedArgs = typeArgs.mapConserve(elimTparams)
        val correctedRes = elimTparams(restpe)
        val hkBound = defn.hkTrait(tparams.length).typeConstructor
          .appliedTo(correctedArgs)
        val result = correctedRes match {
          case TypeBounds(lo, hi) =>
            val hi1 = if (hi == defn.AnyType) hkBound else AndType(hkBound, hi)
            TypeBounds(lo, hi1) //note: using & instead would be too eager
        }
        if ((typeArgs ne correctedArgs) || (restpe ne correctedRes))
          ctx.warning(s"""failure to import F-bounded higher-kinded type
                         |original type definition: ${forSym.show}${tp.show}
                         |definition used instead : ${forSym.show}${result.show}
                         |proceed at own risk.""".stripMargin)
        result
      } else
        PolyType.fromSymbols(tparams, restpe)
    case tp => tp
  }

  /** Convert array parameters denoting a repeated parameter of a Java method
   *  to `JavaRepeatedParamClass` types.
   */
  def arrayToRepeated(tp: Type)(implicit ctx: Context): Type = tp match {
    case tp @ MethodType(paramNames, paramTypes) =>
      val lastArg = paramTypes.last
      assert(lastArg.isArray)
      val elemtp0 :: Nil = lastArg.typeArgs
      val elemtp = elemtp0 match {
        case AndType(t1, t2) if t1.typeSymbol.isAbstractType && t2.isClassType(defn.ObjectClass) =>
          t1 // drop intersection with Object for abstract types in varargs. UnCurry can handle them.
        case _ =>
          elemtp0
      }
      tp.derivedMethodType(
        paramNames,
        paramTypes.init :+ defn.JavaRepeatedParamType.appliedTo(elemtp),
        tp.resultType)
    case tp @ PolyType(paramNames) =>
      tp.derivedPolyType(paramNames, tp.paramBounds, arrayToRepeated(tp.resultType))
  }

  def setClassInfo(denot: ClassDenotation, info: Type, optSelfType: Type = NoType)(implicit ctx: Context): Unit = {
    val cls = denot.classSymbol
    val (tparams, TempClassInfoType(parents, decls, clazz)) = info match {
      case TempPolyType(tps, cinfo) => (tps, cinfo)
      case cinfo => (Nil, cinfo)
    }
    val parentRefs = ctx.normalizeToRefs(parents, cls, decls)
    for (tparam <- tparams) {
      val tsym = decls.lookup(tparam.name)
      if (tsym.exists) tsym.setFlag(TypeParam)
      else denot.enter(tparam, decls)
    }
    val ost =
      if ((optSelfType eq NoType) && (denot is ModuleClass))
        TermRef.withSym(denot.owner.thisType, denot.sourceModule.asTerm)
      else optSelfType
    denot.info = ClassInfo(denot.owner.thisType, denot.classSymbol, parentRefs, decls, ost)
  }
}

/** Unpickle symbol table information descending from a class and/or module root
 *  from an array of bytes.
 *  @param bytes      bytearray from which we unpickle
 *  @param classroot  the top-level class which is unpickled, or NoSymbol if inapplicable
 *  @param moduleroot the top-level module class which is unpickled, or NoSymbol if inapplicable
 *  @param filename   filename associated with bytearray, only used for error messages
 */
class UnPickler(bytes: Array[Byte], classRoot: ClassDenotation, moduleClassRoot: ClassDenotation)(implicit cctx: CondensedContext)
  extends PickleBuffer(bytes, 0, -1) {

  def showPickled() = {
    atReadPos(0, () => {
      println(s"classRoot = ${classRoot.debugString}, moduleClassRoot = ${moduleClassRoot.debugString}")
      util.ShowPickled.printFile(this)
    })
  }

  // print("unpickling "); showPickled() // !!! DEBUG

  import UnPickler._

  import cctx.debug

  val moduleRoot = moduleClassRoot.sourceModule.denot
  assert(moduleRoot.isTerm)

  checkVersion()

  private val loadingMirror = defn // was: mirrorThatLoaded(classRoot)

  /** A map from entry numbers to array offsets */
  private val index = createIndex

  /** A map from entry numbers to symbols, types, or annotations */
  private val entries = new Array[AnyRef](index.length)

  /** A map from symbols to their associated `decls` scopes */
  private val symScopes = mutable.HashMap[Symbol, Scope]()

  /** A map from refinement classes to their associated refinement types */
  private val refinementTypes = mutable.HashMap[Symbol, RefinedType]()

  protected def errorBadSignature(msg: String, original: Option[RuntimeException] = None) = {
    val ex = new BadSignature(
      s"""error reading Scala signature of $classRoot from $source:
         |error occured at position $readIndex: $msg""".stripMargin)
    /*if (debug)*/ original.getOrElse(ex).printStackTrace() // !!! DEBUG
    throw ex
  }

  protected def handleRuntimeException(ex: RuntimeException) = ex match {
    case ex: BadSignature => throw ex
    case _ => errorBadSignature(s"a runtime exception occured: $ex", Some(ex))
  }

  private var postReadOp: () => Unit = null

  def run() =
    try {
      var i = 0
      while (i < index.length) {
        if (entries(i) == null && isSymbolEntry(i)) {
          val savedIndex = readIndex
          readIndex = index(i)
          entries(i) = readSymbol()
          if (postReadOp != null) {
            postReadOp()
            postReadOp = null
          }
          readIndex = savedIndex
        }
        i += 1
      }
      // read children last, fix for #3951
      i = 0
      while (i < index.length) {
        if (entries(i) == null) {
          if (isSymbolAnnotationEntry(i)) {
            val savedIndex = readIndex
            readIndex = index(i)
            readSymbolAnnotation()
            readIndex = savedIndex
          } else if (isChildrenEntry(i)) {
            val savedIndex = readIndex
            readIndex = index(i)
            readChildren()
            readIndex = savedIndex
          }
        }
        i += 1
      }
    } catch {
      case ex: RuntimeException => handleRuntimeException(ex)
    }

  def source: AbstractFile = {
    val f = classRoot.symbol.associatedFile
    if (f != null) f else moduleClassRoot.symbol.associatedFile
  }

  private def checkVersion() {
    val major = readNat()
    val minor = readNat()
    if (major != MajorVersion || minor > MinorVersion)
      throw new IOException("Scala signature " + classRoot.fullName.decode +
        " has wrong version\n expected: " +
        MajorVersion + "." + MinorVersion +
        "\n found: " + major + "." + minor +
        " in " + source)
  }

  /** The `decls` scope associated with given symbol */
  protected def symScope(sym: Symbol) = symScopes.getOrElseUpdate(sym, newScope)

  /** Does entry represent an (internal) symbol */
  protected def isSymbolEntry(i: Int): Boolean = {
    val tag = bytes(index(i)).toInt
    (firstSymTag <= tag && tag <= lastSymTag &&
      (tag != CLASSsym || !isRefinementSymbolEntry(i)))
  }

  /** Does entry represent an (internal or external) symbol */
  protected def isSymbolRef(i: Int): Boolean = {
    val tag = bytes(index(i))
    (firstSymTag <= tag && tag <= lastExtSymTag)
  }

  /** Does entry represent a name? */
  protected def isNameEntry(i: Int): Boolean = {
    val tag = bytes(index(i)).toInt
    tag == TERMname || tag == TYPEname
  }

  /** Does entry represent a symbol annotation? */
  protected def isSymbolAnnotationEntry(i: Int): Boolean = {
    val tag = bytes(index(i)).toInt
    tag == SYMANNOT
  }

  /** Does the entry represent children of a symbol? */
  protected def isChildrenEntry(i: Int): Boolean = {
    val tag = bytes(index(i)).toInt
    tag == CHILDREN
  }

  /** Does entry represent a refinement symbol?
   *  pre: Entry is a class symbol
   */
  protected def isRefinementSymbolEntry(i: Int): Boolean = {
    val savedIndex = readIndex
    readIndex = index(i)
    val tag = readByte().toInt
    assert(tag == CLASSsym)

    readNat(); // read length
    val result = readNameRef() == tpnme.REFINE_CLASS
    readIndex = savedIndex
    result
  }

  protected def isRefinementClass(sym: Symbol): Boolean =
    sym.name == tpnme.REFINE_CLASS

  protected def isLocal(sym: Symbol) = isUnpickleRoot(sym.topLevelClass)

  protected def isUnpickleRoot(sym: Symbol) = {
    val d = sym.denot
    d == moduleRoot || d == moduleClassRoot || d == classRoot
  }

  /** If entry at <code>i</code> is undefined, define it by performing
   *  operation <code>op</code> with <code>readIndex at start of i'th
   *  entry. Restore <code>readIndex</code> afterwards.
   */
  protected def at[T <: AnyRef](i: Int, op: () => T): T = {
    var r = entries(i)
    if (r eq null) {
      r = atReadPos(index(i), op)
      assert(entries(i) eq null, entries(i))
      entries(i) = r
    }
    r.asInstanceOf[T]
  }

  protected def atReadPos[T](start: Int, op: () => T): T = {
    val savedIndex = readIndex
    readIndex = start
    try op()
    finally readIndex = savedIndex
  }

  /** Read a name */
  protected def readName(): Name = {
    val tag = readByte()
    val len = readNat()
    tag match {
      case TERMname => termName(bytes, readIndex, len)
      case TYPEname => typeName(bytes, readIndex, len)
      case _ => errorBadSignature("bad name tag: " + tag)
    }
  }
  protected def readTermName(): TermName = readName().toTermName
  protected def readTypeName(): TypeName = readName().toTypeName

  /** Read a symbol */
  protected def readSymbol(): Symbol = readDisambiguatedSymbol(scala.Function.const(true))()

  /** Read a symbol, with possible disambiguation */
  protected def readDisambiguatedSymbol(p: Symbol => Boolean)(): Symbol = {
    val start = indexCoord(readIndex)
    val tag = readByte()
    val end = readNat() + readIndex
    def atEnd = readIndex == end

    def readExtSymbol(): Symbol = {
      val name = readNameRef()
      val owner = if (atEnd) loadingMirror.RootClass else readSymbolRef()

      def adjust(denot: Denotation) = {
        val denot1 = denot.disambiguate(p)
        val sym = denot1.symbol
        if (denot.exists && !denot1.exists) { // !!!DEBUG
          val alts = denot.alternatives map (d => d+":"+d.info+"/"+d.signature)
          System.err.println(s"!!! disambiguation failure: $alts")
          val members = denot.alternatives.head.symbol.owner.decls.toList map (d => d+":"+d.info+"/"+d.signature)
          System.err.println(s"!!! all members: $members")
        }
        if (tag == EXTref) sym else sym.moduleClass
      }

      def fromName(name: Name): Symbol = name.toTermName match {
        case nme.ROOT => loadingMirror.RootClass
        case nme.ROOTPKG => loadingMirror.RootPackage
        case _ => adjust(owner.info.decl(name))
      }

      def nestedObjectSymbol: Symbol = {
        // If the owner is overloaded (i.e. a method), it's not possible to select the
        // right member, so return NoSymbol. This can only happen when unpickling a tree.
        // the "case Apply" in readTree() takes care of selecting the correct alternative
        //  after parsing the arguments.
        //if (owner.isOverloaded)
        //  return NoSymbol

        if (tag == EXTMODCLASSref) {
          val module = owner.info.decl(name.toTermName).suchThat(_ is Module)
          module.info // force it, as completer does not yet point to module class.
          module.symbol.moduleClass

          /* was:
            val moduleVar = owner.info.decl(name.toTermName.moduleVarName).symbol
            if (moduleVar.isLazyAccessor)
              return moduleVar.lazyAccessor.lazyAccessor
           */
        } else NoSymbol
      }

      // println(s"read ext symbol $name from ${owner.denot.debugString} in ${classRoot.debugString}")  // !!! DEBUG

      // (1) Try name.
      fromName(name) orElse {
        // (2) Try with expanded name.  Can happen if references to private
        // symbols are read from outside: for instance when checking the children
        // of a class.  See #1722.
        fromName(name.toTermName.expandedName(owner)) orElse {
          // (3) Try as a nested object symbol.
          nestedObjectSymbol orElse {
            //              // (4) Call the mirror's "missing" hook.
            adjust(cctx.base.missingHook(owner, name)) orElse {
              // println(owner.info.decls.toList.map(_.debugString).mkString("\n  ")) // !!! DEBUG
              //              }
              // (5) Create a stub symbol to defer hard failure a little longer.
              cctx.newStubSymbol(owner, name, source)
            }
          }
        }
      }
    }

    tag match {
      case NONEsym => return NoSymbol
      case EXTref | EXTMODCLASSref => return readExtSymbol()
      case _ =>
    }

    // symbols that were pickled with Pickler.writeSymInfo
    val nameref = readNat()
    val name0 = at(nameref, readName)
    val owner = readSymbolRef()
    val flags = unpickleScalaFlags(readLongNat(), name0.isTypeName)
    val name = name0.adjustIfModuleClass(flags)

    def isClassRoot = (name == classRoot.name) && (owner == classRoot.owner) && !(flags is ModuleClass)
    def isModuleClassRoot = (name == moduleClassRoot.name) && (owner == moduleClassRoot.owner) && (flags is Module)
    def isModuleRoot = (name == moduleClassRoot.name.toTermName) && (owner == moduleClassRoot.owner) && (flags is Module)

    //if (isClassRoot) println(s"classRoot of $classRoot found at $readIndex, flags = $flags") // !!! DEBUG
    //if (isModuleRoot) println(s"moduleRoot of $moduleRoot found at $readIndex, flags = $flags") // !!! DEBUG
    //if (isModuleClassRoot) println(s"moduleClassRoot of $moduleClassRoot found at $readIndex, flags = $flags") // !!! DEBUG

    def completeRoot(denot: ClassDenotation, completer: LazyType): Symbol = {
      denot.setFlag(flags)
      denot.resetFlag(Touched) // allow one more completion
      denot.info = completer
      denot.symbol
    }

    def finishSym(sym: Symbol): Symbol = {
      if (sym.owner.isClass &&
          !(  isUnpickleRoot(sym)
           || (sym is (ModuleClass | Scala2Existential))
           || ((sym is TypeParam) && !sym.owner.isClass)
           || isRefinementClass(sym)
           )
         ) sym.owner.asClass.enter(sym, symScope(sym.owner))
      sym
    }

    finishSym(tag match {
      case TYPEsym | ALIASsym =>
        var name1 = name.asTypeName
        var flags1 = flags
        if (flags is TypeParam) {
          name1 = name1.expandedName(owner)
          flags1 |= TypeParamCreationFlags | ExpandedName
        }
        cctx.newSymbol(owner, name1, flags1, localMemberUnpickler, coord = start)
      case CLASSsym =>
        val infoRef = readNat()
        postReadOp = () => atReadPos(index(infoRef), readTypeParams) // force reading type params early, so they get entered in the right order.
        if (isClassRoot)
          completeRoot(
            classRoot, rootClassUnpickler(start, classRoot.symbol, NoSymbol))
        else if (isModuleClassRoot)
          completeRoot(
            moduleClassRoot, rootClassUnpickler(start, moduleClassRoot.symbol, moduleClassRoot.sourceModule))
        else if (name == tpnme.REFINE_CLASS)
          // create a type alias instead
          cctx.newSymbol(owner, name, flags, localMemberUnpickler, coord = start)
        else {
          def completer(cls: Symbol) = new LocalClassUnpickler(cls) {
            override def sourceModule =
              if (flags is ModuleClass)
                cls.owner.decls.lookup(cls.name.stripModuleClassSuffix.toTermName)
                  .suchThat(_ is Module).symbol
              else NoSymbol
          }
          cctx.newClassSymbol(owner, name.asTypeName, flags, completer, coord = start)
        }
      case MODULEsym | VALsym =>
        if (isModuleRoot) {
          moduleRoot setFlag flags
          moduleRoot.symbol
        } else cctx.newSymbol(owner, name.asTermName, flags, localMemberUnpickler, coord = start)
      case _ =>
        errorBadSignature("bad symbol tag: " + tag)
    })
  }

  abstract class LocalUnpickler extends LazyType {
    def parseToCompletion(denot: SymDenotation) = {
      val tag = readByte()
      val end = readNat() + readIndex
      def atEnd = readIndex == end
      val unusedNameref = readNat()
      val unusedOwnerref = readNat()
      val unusedFlags = readLongNat()
      var inforef = readNat()
      denot.privateWithin =
        if (!isSymbolRef(inforef)) NoSymbol
        else {
          val pw = at(inforef, readSymbol)
          inforef = readNat()
          pw
        }
      // println("reading type for "+denot) // !!! DEBUG
      val tp = at(inforef, readType)
      denot match {
        case denot: ClassDenotation =>
          val optSelfType = if (atEnd) NoType else readTypeRef()
          setClassInfo(denot, tp, optSelfType)
        case denot =>
          val tp1 = depoly(tp, denot)
          denot.info = if (tag == ALIASsym) TypeAlias(tp1) else tp1
          if (atEnd) {
            assert(!(denot is SuperAccessor), denot)
          } else {
            assert(denot is (SuperAccessor | ParamAccessor), denot)
            def disambiguate(alt: Symbol) = { // !!! DEBUG
              cctx.debugTraceIndented(s"disambiguating ${denot.info} =:= ${ denot.owner.thisType.memberInfo(alt)} ${denot.owner}") {
                denot.info matches denot.owner.thisType.memberInfo(alt)
              }
            }
            val alias = readDisambiguatedSymbolRef(disambiguate).asTerm
            denot.addAnnotation(Annotation.makeAlias(alias))
          }
      }
      // println(s"unpickled ${denot.debugString}, info = ${denot.info}") !!! DEBUG
    }
    def startCoord(denot: SymDenotation): Coord = denot.symbol.coord
    def complete(denot: SymDenotation): Unit = try {
      atReadPos(startCoord(denot).toIndex, () => parseToCompletion(denot))
    } catch {
      case ex: RuntimeException => handleRuntimeException(ex)
    }
  }

  class AtStartUnpickler(start: Coord) extends LocalUnpickler {
    override def startCoord(denot: SymDenotation): Coord = start
  }

  object localMemberUnpickler extends LocalUnpickler

  class LocalClassUnpickler(cls: Symbol)
    extends ClassCompleterWithDecls(symScope(cls), localMemberUnpickler)

  def rootClassUnpickler(start: Coord, cls: Symbol, module: Symbol) =
    new ClassCompleterWithDecls(symScope(cls), new AtStartUnpickler(start)) with SymbolLoaders.SecondCompleter {
      override def sourceModule = module
    }

  /** Convert
   *    tp { type name = sym } forSome { sym >: L <: H }
   *  to
   *    tp { name >: L <: H }
   *  and
   *    tp { name: sym } forSome { sym <: T with Singleton }
   *  to
   *    tp { name: T }
   */
  def elimExistentials(boundSyms: List[Symbol], tp: Type): Type = {
    def removeSingleton(tp: Type): Type =
      if (tp.isClassType(defn.SingletonClass)) defn.AnyType else tp
    def elim(tp: Type): Type = tp match {
      case tp @ RefinedType(parent, name) =>
        val parent1 = elim(tp.parent)
        tp.refinedInfo match {
          case TypeAlias(info: TypeRefBySym) if boundSyms contains info.fixedSym =>
            RefinedType(parent1, name, info.fixedSym.info)
          case info: TypeRefBySym if boundSyms contains info.fixedSym =>
            val info1 = info.fixedSym.info
            assert(info1.derivesFrom(defn.SingletonClass))
            RefinedType(parent1, name, info1.mapAnd(removeSingleton))
          case info =>
            tp.derivedRefinedType(parent1, name, info)
        }
      case _ =>
        tp
    }
    val tp1 = elim(tp)
    val isBound = (tp: Type) => boundSyms contains tp.typeSymbol
    if (tp1 existsPart isBound) {
      val anyTypes = boundSyms map (_ => defn.AnyType)
      val boundBounds = boundSyms map (_.info.bounds.hi)
      val tp2 = tp1.subst(boundSyms, boundBounds).subst(boundSyms, anyTypes)
      cctx.warning(s"""failure to eliminate existential
                       |original type    : $tp forSome {${cctx.dclsText(boundSyms, "; ").show}
                       |reduces to       : $tp1
                       |type used instead: $tp2
                       |proceed at own risk.""".stripMargin)
      tp2
    } else tp1
  }

  /** Read a type
   *
   *  @param forceProperType is used to ease the transition to NullaryMethodTypes (commentmarker: NMT_TRANSITION)
   *        the flag say that a type of kind * is expected, so that PolyType(tps, restpe) can be disambiguated to PolyType(tps, NullaryMethodType(restpe))
   *        (if restpe is not a ClassInfoType, a MethodType or a NullaryMethodType, which leaves TypeRef/SingletonType -- the latter would make the polytype a type constructor)
   */
  protected def readType(): Type = {
    val tag = readByte()
    val end = readNat() + readIndex
    (tag: @switch) match {
      case NOtpe =>
        NoType
      case NOPREFIXtpe =>
        NoPrefix
      case THIStpe =>
        val cls = readSymbolRef().asClass
        if (isRefinementClass(cls)) RefinedThis(refinementTypes(cls))
        else ThisType(cls)
      case SINGLEtpe =>
        val pre = readTypeRef()
        val sym = readDisambiguatedSymbolRef(_.isParameterless)
        if (isLocal(sym) || (pre == NoPrefix)) TermRef.withSym(pre, sym.asTerm)
        else TermRef.withSig(pre, sym.name.asTermName, NotAMethod)
      case SUPERtpe =>
        val thistpe = readTypeRef()
        val supertpe = readTypeRef()
        SuperType(thistpe, supertpe)
      case CONSTANTtpe =>
        ConstantType(readConstantRef())
      case TYPEREFtpe =>
        var pre = readTypeRef()
        val sym = readSymbolRef()
        pre match {
          case ThisType(cls) =>
            // The problem is that class references super.C get pickled as
            // this.C. Dereferencing the member might then get an overriding class
            // instance. The problem arises for instance for LinkedHashMap#MapValues
            // and also for the inner Transform class in all views. We fix it by
            // replacing the this with the appropriate super.
            if (sym.owner != cls) {
              val overriding = cls.decls.lookup(sym.name)
              if (overriding.exists && overriding != sym) {
                val base = pre.baseType(sym.owner)
                assert(base.exists)
                pre = SuperType(pre, base)
              }
            }
          case _ =>
        }
        val tycon =
          if (isLocal(sym) || pre == NoPrefix) {
            TypeRef.withSym(
              if ((pre eq NoPrefix) && (sym is TypeParam)) sym.owner.thisType else pre,
              sym.asType)
          } else TypeRef(pre, sym.name.asTypeName)
        val args = until(end, readTypeRef)
//        if (args.nonEmpty) { // DEBUG
//           println(s"reading app type $tycon $args")
//        }
        tycon.appliedTo(args)
      case TYPEBOUNDStpe =>
        TypeBounds(readTypeRef(), readTypeRef())
      case REFINEDtpe =>
        val clazz = readSymbolRef()
        val decls = symScope(clazz)
        symScopes(clazz) = EmptyScope // prevent further additions
        val parents = until(end, readTypeRef)
        val parent = parents.reduceLeft(AndType(_, _))
        if (decls.isEmpty) parent
        else {
          def addRefinement(tp: Type, sym: Symbol) =
            RefinedType(tp, sym.name, sym.info)
          val result = (parent /: decls.toList)(addRefinement).asInstanceOf[RefinedType]
          assert(!refinementTypes.isDefinedAt(clazz), clazz + "/" + decls)
          refinementTypes(clazz) = result
          result
        }
      case CLASSINFOtpe =>
        val clazz = readSymbolRef()
        TempClassInfoType(until(end, readTypeRef), symScope(clazz), clazz)
      case METHODtpe | IMPLICITMETHODtpe =>
        val restpe = readTypeRef()
        val params = until(end, readSymbolRef)
        def isImplicit =
          tag == IMPLICITMETHODtpe ||
          params.nonEmpty && (params.head is Implicit)
        val maker = if (isImplicit) ImplicitMethodType else MethodType
        maker.fromSymbols(params, restpe)
      case POLYtpe =>
        val restpe = readTypeRef()
        val typeParams = until(end, readSymbolRef)
        def deExpr(tp: Type) = tp match {
          case ExprType(restpe) => restpe
          case tp => tp
        }
        if (typeParams.nonEmpty) TempPolyType(typeParams, deExpr(restpe))
        else ExprType(restpe)
      case EXISTENTIALtpe =>
        val restpe = readTypeRef()
        val boundSyms = until(end, readSymbolRef)
        elimExistentials(boundSyms, restpe)
      case ANNOTATEDtpe =>
        val tp = readTypeRef()
        // no annotation self type is supported, so no test whether this is a symbol ref
        val annots = until(end, readAnnotationRef)
        AnnotatedType.make(annots, tp)
      case _ =>
        noSuchTypeTag(tag, end)
    }
  }

  def readTypeParams(): List[Symbol] = {
    val tag = readByte()
    val end = readNat() + readIndex
    if (tag == POLYtpe) {
      val unusedRestperef = readNat()
      until(end, readSymbolRef)
    } else Nil
  }

  def noSuchTypeTag(tag: Int, end: Int): Type =
    errorBadSignature("bad type tag: " + tag)

  /** Read a constant */
  protected def readConstant(): Constant = {
    val tag = readByte().toInt
    val len = readNat()
    (tag: @switch) match {
      case LITERALunit => Constant(())
      case LITERALboolean => Constant(readLong(len) != 0L)
      case LITERALbyte => Constant(readLong(len).toByte)
      case LITERALshort => Constant(readLong(len).toShort)
      case LITERALchar => Constant(readLong(len).toChar)
      case LITERALint => Constant(readLong(len).toInt)
      case LITERALlong => Constant(readLong(len))
      case LITERALfloat => Constant(intBitsToFloat(readLong(len).toInt))
      case LITERALdouble => Constant(longBitsToDouble(readLong(len)))
      case LITERALstring => Constant(readNameRef().toString)
      case LITERALnull => Constant(null)
      case LITERALclass => Constant(readTypeRef())
      case LITERALenum => Constant(readSymbolRef())
      case _ => noSuchConstantTag(tag, len)
    }
  }

  def noSuchConstantTag(tag: Int, len: Int): Constant =
    errorBadSignature("bad constant tag: " + tag)

  /** Read children and store them into the corresponding symbol.
   */
  protected def readChildren() {
    val tag = readByte()
    assert(tag == CHILDREN)
    val end = readNat() + readIndex
    val target = readSymbolRef()
    while (readIndex != end)
      target.addAnnotation(Annotation.makeChild(readSymbolRef()))
  }

  /* Read a reference to a pickled item */
  protected def readSymbolRef(): Symbol = { //OPT inlined from: at(readNat(), readSymbol) to save on closure creation
    val i = readNat()
    var r = entries(i)
    if (r eq null) {
      val savedIndex = readIndex
      readIndex = index(i)
      r = readSymbol()
      assert(entries(i) eq null, entries(i))
      entries(i) = r
      readIndex = savedIndex
    }
    r.asInstanceOf[Symbol]
  }

  protected def readDisambiguatedSymbolRef(p: Symbol => Boolean): Symbol =
    at(readNat(), readDisambiguatedSymbol(p))

  protected def readNameRef(): Name = at(readNat(), readName)
  protected def readTypeRef(): Type = at(readNat(), () => readType()) // after the NMT_TRANSITION period, we can leave off the () => ... ()
  protected def readConstantRef(): Constant = at(readNat(), readConstant)

  protected def readTypeNameRef(): TypeName = readNameRef().toTypeName
  protected def readTermNameRef(): TermName = readNameRef().toTermName

  protected def readAnnotationRef(): Annotation = at(readNat(), readAnnotation)

  protected def readModifiersRef(isType: Boolean): Modifiers = at(readNat(), () => readModifiers(isType))
  protected def readTreeRef(): Tree = at(readNat(), readTree)

  /** Read an annotation argument, which is pickled either
   *  as a Constant or a Tree.
   */
  protected def readAnnotArg(i: Int): Tree = bytes(index(i)) match {
    case TREE => at(i, readTree)
    case _ => Literal(at(i, readConstant))
  }

  /** Read a ClassfileAnnotArg (argument to a classfile annotation)
   */
  private def readArrayAnnotArg(): Tree = {
    readByte() // skip the `annotargarray` tag
    val end = readNat() + readIndex
    // array elements are trees representing instances of scala.annotation.Annotation
    SeqLiteral(
      TypeTree(defn.AnnotationClass.typeConstructor),
      until(end, () => readClassfileAnnotArg(readNat())))
  }

  private def readAnnotInfoArg(): Tree = {
    readByte() // skip the `annotinfo` tag
    val end = readNat() + readIndex
    readAnnotationContents(end)
  }

  protected def readClassfileAnnotArg(i: Int): Tree = bytes(index(i)) match {
    case ANNOTINFO => at(i, readAnnotInfoArg)
    case ANNOTARGARRAY => at(i, readArrayAnnotArg)
    case _ => readAnnotArg(i)
  }

  /** Read an annotation's contents. Not to be called directly, use
   *  readAnnotation, readSymbolAnnotation, or readAnnotInfoArg
   */
  protected def readAnnotationContents(end: Int): Tree = {
    val atp = readTypeRef()
    val args = new ListBuffer[Tree]
    while (readIndex != end) {
      val argref = readNat()
      args += {
        if (isNameEntry(argref)) {
          val name = at(argref, readName)
          val arg = readClassfileAnnotArg(readNat())
          NamedArg(name.asTermName, arg)
        } else readAnnotArg(argref)
      }
    }
    New(atp, args.toList)
  }

  /** Read an annotation and as a side effect store it into
   *  the symbol it requests. Called at top-level, for all
   *  (symbol, annotInfo) entries.
   */
  protected def readSymbolAnnotation(): Unit = {
    val tag = readByte()
    if (tag != SYMANNOT)
      errorBadSignature("symbol annotation expected (" + tag + ")")
    val end = readNat() + readIndex
    val target = readSymbolRef()
    target.addAnnotation(deferredAnnot(end))
  }

  /** Read an annotation and return it. Used when unpickling
   *  an ANNOTATED(WSELF)tpe or a NestedAnnotArg
   */
  protected def readAnnotation(): Annotation = {
    val tag = readByte()
    if (tag != ANNOTINFO)
      errorBadSignature("annotation expected (" + tag + ")")
    val end = readNat() + readIndex
    deferredAnnot(end)
  }

  /** A deferred annotation that can be comleted by reading
   *  the bytes between `readIndex` and `end`.
   */
  protected def deferredAnnot(end: Int): Annotation = {
    val start = readIndex
    val atp = readTypeRef()
    Annotation.deferred(
      atp.typeSymbol, atReadPos(start, () => readAnnotationContents(end)))
  }

  /* Read an abstract syntax tree */
  protected def readTree(): Tree = {
    val outerTag = readByte()
    if (outerTag != TREE)
      errorBadSignature("tree expected (" + outerTag + ")")
    val end = readNat() + readIndex
    val tag = readByte()
    val tpe = if (tag == EMPTYtree) NoType else readTypeRef()

    // Set by the three functions to follow.  If symbol is non-null
    // after the new tree 't' has been created, t has its Symbol
    // set to symbol; and it always has its Type set to tpe.
    var symbol: Symbol = null
    var mods: Modifiers = null
    var name: Name = null

    /** Read a Symbol, Modifiers, and a Name */
    def setSymModsName() {
      symbol = readSymbolRef()
      mods = readModifiersRef(symbol.isType)
      name = readNameRef()
    }
    /** Read a Symbol and a Name */
    def setSymName() {
      symbol = readSymbolRef()
      name = readNameRef()
    }
    /** Read a Symbol */
    def setSym() {
      symbol = readSymbolRef()
    }

    implicit val pos: Position = NoPosition

    tag match {
      case EMPTYtree =>
        EmptyTree

      case PACKAGEtree =>
        setSym()
        val pid = readTreeRef().asInstanceOf[RefTree]
        val stats = until(end, readTreeRef)
        PackageDef(pid, stats)

      case CLASStree =>
        setSymModsName()
        val impl = readTemplateRef()
        val tparams = until(end, readTypeDefRef)
        ClassDef(symbol.asClass, tparams map (_.symbol.asType), ???, impl.body) // !!! TODO: pull out primary constructor

      case MODULEtree =>
        setSymModsName()
        ModuleDef(symbol.asTerm, readTemplateRef().body)

      case VALDEFtree =>
        setSymModsName()
        val tpt = readTreeRef()
        val rhs = readTreeRef()
        ValDef(symbol.asTerm, rhs)

      case DEFDEFtree =>
        setSymModsName()
        val tparams = times(readNat(), readTypeDefRef)
        val vparamss = times(readNat(), () => times(readNat(), readValDefRef))
        val tpt = readTreeRef()
        val rhs = readTreeRef()
        DefDef(symbol.asTerm, rhs)

      case TYPEDEFtree =>
        setSymModsName()
        val rhs = readTreeRef()
        val tparams = until(end, readTypeDefRef)
        TypeDef(symbol.asType)

      case LABELtree =>
        setSymName()
        val rhs = readTreeRef()
        val params = until(end, readIdentRef)
        val ldef = DefDef(symbol.asTerm, rhs)
        def isCaseLabel(sym: Symbol) = sym.name.startsWith(nme.CASEkw)
        if (isCaseLabel(symbol)) ldef
        else Block(ldef :: Nil, Apply(Ident(refType(symbol)), Nil))

      case IMPORTtree =>
        setSym()
        val expr = readTreeRef()
        val selectors = until(end, () => {
          val fromName = readNameRef()
          val toName = readNameRef()
          val from = Trees.Ident(fromName)
          val to = Trees.Ident(toName)
          if (toName.isEmpty) from else Trees.Pair(from, Trees.Ident(toName))
        })

        Import(expr, selectors)

      case TEMPLATEtree =>
        setSym()
        val parents = times(readNat(), readTreeRef)
        val self = readValDefRef()
        val body = until(end, readTreeRef)
        Trees.Template[Type](???, parents, self, body) // !!! TODO: pull out primary constructor
          .withType(refType(symbol))

      case BLOCKtree =>
        val expr = readTreeRef()
        val stats = until(end, readTreeRef)
        Block(stats, expr)

      case CASEtree =>
        val pat = readTreeRef()
        val guard = readTreeRef()
        val body = readTreeRef()
        CaseDef(pat, guard, body)

      case ALTERNATIVEtree =>
        Alternative(until(end, readTreeRef))

      case STARtree =>
        readTreeRef()
        unimplementedTree("STAR")

      case BINDtree =>
        setSymName()
        Bind(symbol.asTerm, readTreeRef())

      case UNAPPLYtree =>
        val fun = readTreeRef()
        val args = until(end, readTreeRef)
        UnApply(fun, args)

      case ARRAYVALUEtree =>
        val elemtpt = readTreeRef()
        val trees = until(end, readTreeRef)
        SeqLiteral(elemtpt, trees)

      case FUNCTIONtree =>
        setSym()
        val body = readTreeRef()
        val vparams = until(end, readValDefRef)
        val applyType = MethodType(vparams map (_.name), vparams map (_.tpt.tpe), body.tpe)
        val applyMeth = cctx.newSymbol(symbol.owner, nme.apply, Method, applyType)
        Function(applyMeth, body.changeOwner(symbol, applyMeth), tpe)

      case ASSIGNtree =>
        val lhs = readTreeRef()
        val rhs = readTreeRef()
        Assign(lhs, rhs)

      case IFtree =>
        val cond = readTreeRef()
        val thenp = readTreeRef()
        val elsep = readTreeRef()
        If(cond, thenp, elsep)

      case MATCHtree =>
        val selector = readTreeRef()
        val cases = until(end, readCaseDefRef)
        Match(selector, cases)

      case RETURNtree =>
        setSym()
        Return(readTreeRef(), Ident(refType(symbol)))

      case TREtree =>
        val block = readTreeRef()
        val finalizer = readTreeRef()
        val catches = until(end, readCaseDefRef)
        Try(block, Match(EmptyTree, catches), finalizer)

      case THROWtree =>
        Throw(readTreeRef())

      case NEWtree =>
        New(readTreeRef().tpe)

      case TYPEDtree =>
        val expr = readTreeRef()
        val tpt = readTreeRef()
        Typed(expr, tpt)

      case TYPEAPPLYtree =>
        val fun = readTreeRef()
        val args = until(end, readTreeRef)
        TypeApply(fun, args)

      case APPLYtree =>
        val fun = readTreeRef()
        val args = until(end, readTreeRef)
        /*
          if (fun.symbol.isOverloaded) {
            fun.setType(fun.symbol.info)
            inferMethodAlternative(fun, args map (_.tpe), tpe)
          }
*/
        Apply(fun, args) // note: can't deal with overloaded syms yet

      case APPLYDYNAMICtree =>
        setSym()
        val qual = readTreeRef()
        val args = until(end, readTreeRef)
        unimplementedTree("APPLYDYNAMIC")

      case SUPERtree =>
        setSym()
        val qual = readTreeRef()
        val mix = readTypeNameRef()
        Super(qual, mix)

      case THIStree =>
        setSym()
        val name = readTypeNameRef()
        This(symbol.asClass)

      case SELECTtree =>
        setSym()
        val qualifier = readTreeRef()
        val selector = readNameRef()
        Select(qualifier, refType(symbol))

      case IDENTtree =>
        setSymName()
        Ident(refType(symbol))

      case LITERALtree =>
        Literal(readConstantRef())

      case TYPEtree =>
        TypeTree(tpe)

      case ANNOTATEDtree =>
        val annot = readTreeRef()
        val arg = readTreeRef()
        Annotated(annot, arg)

      case SINGLETONTYPEtree =>
        SingletonTypeTree(readTreeRef())

      case SELECTFROMTYPEtree =>
        val qualifier = readTreeRef()
        val selector = readTypeNameRef()
        SelectFromTypeTree(qualifier, refType(symbol))

      case COMPOUNDTYPEtree =>
        readTemplateRef()
        TypeTree(tpe)

      case APPLIEDTYPEtree =>
        val tpt = readTreeRef()
        val args = until(end, readTreeRef)
        AppliedTypeTree(tpt, args)

      case TYPEBOUNDStree =>
        val lo = readTreeRef()
        val hi = readTreeRef()
        TypeBoundsTree(lo, hi)

      case EXISTENTIALTYPEtree =>
        val tpt = readTreeRef()
        val whereClauses = until(end, readTreeRef)
        TypeTree(tpe)

      case _ =>
        noSuchTreeTag(tag, end)
    }
  }

  def noSuchTreeTag(tag: Int, end: Int) =
    errorBadSignature("unknown tree type (" + tag + ")")

  def unimplementedTree(what: String) =
    errorBadSignature(s"cannot read $what trees from Scala 2.x signatures")

  def readModifiers(isType: Boolean): Modifiers = {
    val tag = readNat()
    if (tag != MODIFIERS)
      errorBadSignature("expected a modifiers tag (" + tag + ")")
    val end = readNat() + readIndex
    val pflagsHi = readNat()
    val pflagsLo = readNat()
    val pflags = (pflagsHi.toLong << 32) + pflagsLo
    val flags = unpickleScalaFlags(pflags, isType)
    val privateWithin = readNameRef().asTypeName
    Trees.Modifiers[Type](flags, privateWithin, Nil)
  }

  protected def readTemplateRef(): Template =
    readTreeRef() match {
      case templ: Template => templ
      case other =>
        errorBadSignature("expected a template (" + other + ")")
    }
  protected def readCaseDefRef(): CaseDef =
    readTreeRef() match {
      case tree: CaseDef => tree
      case other =>
        errorBadSignature("expected a case def (" + other + ")")
    }
  protected def readValDefRef(): ValDef =
    readTreeRef() match {
      case tree: ValDef => tree
      case other =>
        errorBadSignature("expected a ValDef (" + other + ")")
    }
  protected def readIdentRef(): Ident =
    readTreeRef() match {
      case tree: Ident => tree
      case other =>
        errorBadSignature("expected an Ident (" + other + ")")
    }
  protected def readTypeDefRef(): TypeDef =
    readTreeRef() match {
      case tree: TypeDef => tree
      case other =>
        errorBadSignature("expected an TypeDef (" + other + ")")
    }

}
