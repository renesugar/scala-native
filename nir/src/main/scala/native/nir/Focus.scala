package native
package nir

import scala.collection.mutable

final case class Focus(
  val blocks:     Seq[Block],
  val name:       Local,
  val params:     Seq[Val.Local],
  val insts:      Seq[Inst],
  val value:      Val,
  val isComplete: Boolean
)(implicit _fresh: Fresh) {
  def fresh = _fresh

  def appendBlocks(blocks: Seq[Block]) =
    copy(blocks = this.blocks ++ blocks)

  def prependBlocks(blocks: Seq[Block]) =
    copy(blocks = blocks ++ this.blocks)

  def withValue(newvalue: Val): Focus = {
    assert(!isComplete)
    copy(value = newvalue)
  }

  def withOp(op: Op): Focus = {
    assert(!isComplete)
    val name = fresh()
    copy(insts = insts :+ Inst(Some(name), op), value = Val.Local(name, op.resty))
  }

  def finish(op: Op): Focus =
    finish(Inst(None, op))

  def finish(inst: Inst): Focus =
    if (isComplete) this
    else Focus.complete(blocks :+ Block(name, params, insts :+ inst))

  private def wrapBranch(merge: Local, f: Focus => Focus, params: Seq[Val.Local] = Seq()) = {
    val entry = Focus.entry(params)
    val end = f(entry)
    val finalized =
      if (end.isComplete) end
      else end.finish(Op.Jump(Next(merge, Seq(end.value))))
    (entry.name, end.isComplete, finalized.blocks)
  }

  def branchIf(cond: Val, retty: Type,
               thenf: Focus => Focus, elsef: Focus => Focus): Focus = {
    val merge = fresh()
    val param = Val.Local(fresh(), retty)
    val (thenname, thencompl, thenblocks) = wrapBranch(merge, thenf)
    val (elsename, elsecompl, elseblocks) = wrapBranch(merge, elsef)
    val blocks =
      finish(Op.If(cond,
        Next(thenname, Seq()),
        Next(elsename, Seq()))).blocks
    if (thencompl && elsecompl)
      Focus.complete(blocks ++ thenblocks ++ elseblocks)
    else
      Focus(blocks ++ thenblocks ++ elseblocks,
            merge, Seq(param), Seq(),
            param, isComplete = false)
  }

  def branchSwitch(scrut: Val, retty: Type,
                   defaultf: Focus => Focus,
                   casevals: Seq[Val], casefs: Seq[Focus => Focus]): Focus = {
    val merge = fresh()
    val param = Val.Local(fresh(), retty)
    val (defaultname, defaultcompl, defaultblocks) = wrapBranch(merge, defaultf)
    val cases = casefs.map(wrapBranch(merge, _))
    val casenames = cases.map(_._1)
    val casecompl = cases.map(_._2)
    val caseblockss = cases.map(_._3)
    val blocks =
      finish(Op.Switch(scrut,
        Next(defaultname, Seq()),
        casevals.zip(casenames).map { case (v, n) => Case(v, Next(n, Seq())) })).blocks
    Focus(blocks ++ defaultblocks ++ caseblockss.flatten,
          merge, Seq(param), Seq(),
          param, isComplete = false)
  }

  def branchTry(retty: Type, normal: Focus => Focus, exc: (Val, Focus) => Focus): Focus = {
    val merge = fresh()
    val excparam = Val.Local(fresh(), Intr.object_)
    val param = Val.Local(fresh(), retty)
    val (normname, normcompl, normblocks) = wrapBranch(merge, normal)
    val (excname, exccompl, excblocks) = wrapBranch(merge, exc(excparam, _), Seq(excparam))
    val blocks =
      finish(Op.Try(
        Next(normname, Seq()),
        Next(excname, Seq()))).blocks
    if (normcompl && exccompl)
      Focus.complete(blocks ++ normblocks ++ excblocks)
    else
      Focus(blocks ++ normblocks ++ excblocks,
            merge, Seq(param), Seq(),
            param, isComplete = false)
  }
}
object Focus {
  final case class NotMergeable(focus: Focus) extends Exception

  def entry(implicit fresh: Fresh): Focus =
    entry(fresh(), Seq())

  def entry(params: Seq[Val.Local])(implicit fresh: Fresh): Focus =
    entry(fresh(), params)

  def entry(name: Local, params: Seq[Val.Local])(implicit fresh: Fresh): Focus =
    Focus(Seq(), name, params, Seq(), Val.Unit, isComplete = false)

  def complete(blocks: Seq[Block])(implicit fresh: Fresh)=
    Focus(blocks, Local("", -1), Seq(), Seq(), Val.Unit, isComplete = true)

  def sequenced[T](elems: Seq[T], focus: Focus)
                  (f: (T, Focus) => Focus): Seq[Focus] = {
    val focs = new mutable.ListBuffer[Focus]
    elems.foldLeft(focus) { (accfoc, elem) =>
      val efoc = f(elem, accfoc)
      focs += efoc
      efoc
    }
    focs.toSeq
  }
}