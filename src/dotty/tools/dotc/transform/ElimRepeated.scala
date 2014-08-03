package dotty.tools.dotc
package transform

import core._
import Names._
import Types._
import TreeTransforms.{TransformerInfo, TreeTransform, TreeTransformer}
import ast.Trees.flatten
import Flags._
import Contexts.Context
import Symbols._
import Denotations._, SymDenotations._
import Decorators.StringInterpolators
import scala.collection.mutable
import DenotTransformers._
import Names.Name
import NameOps._
import TypeUtils._

/** A transformer that removes repeated parameters (T*) from all types, replacing
 *  them with Seq types.
 */
class ElimRepeated extends TreeTransform with InfoTransformer { thisTransformer =>
  import ast.tpd._

  override def name = "elimrepeated"

  def transformInfo(tp: Type, sym: Symbol)(implicit ctx: Context): Type =
    elimRepeated(tp)

  private def elimRepeated(tp: Type)(implicit ctx: Context): Type = tp.stripTypeVar match {
    case tp @ MethodType(paramNames, paramTypes) =>
      val resultType1 = elimRepeated(tp.resultType)
      val paramTypes1 =
        if (paramTypes.nonEmpty && paramTypes.last.isRepeatedParam)
          paramTypes.init :+ paramTypes.last.underlyingIfRepeated(tp.isJava)
        else paramTypes
      tp.derivedMethodType(paramNames, paramTypes1, resultType1)
    case tp: PolyType =>
      tp.derivedPolyType(tp.paramNames, tp.paramBounds, elimRepeated(tp.resultType))
    case tp =>
      tp
  }

  def transformTypeOfTree(tree: Tree)(implicit ctx: Context): Tree =
    tree.withType(elimRepeated(tree.tpe))

  override def transformIdent(tree: Ident)(implicit ctx: Context, info: TransformerInfo): Tree =
    transformTypeOfTree(tree)

  override def transformSelect(tree: Select)(implicit ctx: Context, info: TransformerInfo): Tree =
    transformTypeOfTree(tree)

  override def transformApply(tree: Apply)(implicit ctx: Context, info: TransformerInfo): Tree =
    transformTypeOfTree(tree)

  override def transformTypeApply(tree: TypeApply)(implicit ctx: Context, info: TransformerInfo): Tree =
    transformTypeOfTree(tree)

  override def transformDefDef(tree: DefDef)(implicit ctx: Context, info: TransformerInfo): Tree = {
    assert(ctx.phase == thisTransformer)
    def overridesJava = {
      val overridden = tree.symbol.allOverriddenSymbols
      overridden.hasNext && overridden.forall(_ is JavaDefined)
    }
    if (tree.symbol.info.isVarArgsMethod && overridesJava)
      addVarArgsBridge(tree)(ctx.withPhase(thisTransformer.next))
    else
      tree
  }

  /** add varargs bridge method
   */
  private def addVarArgsBridge(ddef: DefDef)(implicit ctx: Context): Tree = {
    val original = ddef.symbol.asTerm
    val bridge = original.copy(
      flags = ddef.symbol.flags &~ Private | Artifact,
      info = toJavaVarArgs(ddef.symbol.info)).enteredAfter(thisTransformer).asTerm
    val bridgeDef = polyDefDef(bridge, trefs => vrefss => {
      val (vrefs :+ varArgRef) :: vrefss1 = vrefss
      val elemtp = varArgRef.tpe.widen.argTypes.head
      ref(original.termRef)
        .appliedToTypes(trefs)
        .appliedToArgs(vrefs :+ TreeGen.wrapArray(varArgRef, elemtp))
        .appliedToArgss(vrefss1)
    })
    Thicket(ddef, bridgeDef)
  }

  private def toJavaVarArgs(tp: Type)(implicit ctx: Context): Type = tp match {
    case tp: PolyType =>
      tp.derivedPolyType(tp.paramNames, tp.paramBounds, toJavaVarArgs(tp.resultType))
    case tp: MethodType =>
      val inits :+ last = tp.paramTypes
      val last1 = last.underlyingIfRepeated(isJava = true)
      tp.derivedMethodType(tp.paramNames, inits :+ last1, tp.resultType)
  }
}
