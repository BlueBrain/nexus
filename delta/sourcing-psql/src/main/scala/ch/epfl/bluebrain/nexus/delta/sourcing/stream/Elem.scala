package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SkippedElem, SuccessElem}

/**
  * Enumeration of projection element states.
  *
  * @tparam A
  *   the value type of the element
  */
sealed trait Elem[+A] extends Product with Serializable {

  /**
    * @return
    *   the element contextual information
    */
  def ctx: ElemCtx

  /**
    * Constructs a new [[Elem]] of the same type as this with the provided `ctx` value.
    * @param ctx
    *   the new context
    */
  def withCtx(ctx: ElemCtx): Elem[A] = this match {
    case SuccessElem(_, value) => SuccessElem(ctx, value)
    case FailedElem(_, reason) => FailedElem(ctx, reason)
    case DroppedElem(_)        => DroppedElem(ctx)
    case SkippedElem(_)        => SkippedElem(ctx)
  }
}

object Elem {

  /**
    * An element that has a value of type [[A]] that has been previously successfully processed.
    * @param ctx
    *   the element contextual information
    * @param value
    *   the element value
    * @tparam A
    *   the value type of the element
    */
  final case class SuccessElem[+A](ctx: ElemCtx, value: A) extends Elem[A]

  /**
    * An element that has suffered a processing failure.
    * @param ctx
    *   the element contextual information
    * @param reason
    *   a human readable reason for why processing has failed for this element
    */
  final case class FailedElem(ctx: ElemCtx, reason: String) extends Elem[Nothing]

  /**
    * An element that was discarded through filtering.
    * @param ctx
    *   the element contextual information
    */
  final case class DroppedElem(ctx: ElemCtx) extends Elem[Nothing]

  /**
    * An element that was skipped during a partial re-execution of a projection.
    * @param ctx
    *   the element contextual information
    */
  final case class SkippedElem(ctx: ElemCtx) extends Elem[Nothing]
}
