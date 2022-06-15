package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}

import java.time.Instant

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
    * @return
    *   the underlying entity type
    */
  def tpe: EntityType

  /**
    * @return
    *   the underlying entity id
    */
  def id: Iri

  /**
    * @return
    *   the underlying entity revision
    */
  def rev: Int

  /**
    * @return
    *   the instant when the element was produced
    */
  def instant: Instant

  /**
    * @return
    *   the element offset
    */
  def offset: Offset

  /**
    * Constructs a new [[Elem]] of the same type as this with the provided `ctx` value.
    * @param ctx
    *   the new context
    */
  def withCtx(ctx: ElemCtx): Elem[A] = this match {
    case e: SuccessElem[A] => e.copy(ctx = ctx)
    case e: FailedElem     => e.copy(ctx = ctx)
    case e: DroppedElem    => e.copy(ctx = ctx)
  }

  /**
    * Produces a new [[FailedElem]] with the provided reason copying the common properties
    * @param reason
    *   the reason why the element processing failed
    */
  def failed(reason: String): FailedElem =
    FailedElem(ctx, tpe, id, rev, instant, offset, reason)

  /**
    * Produces a new [[SuccessElem]] with the provided value copying the common properties.
    * @param value
    *   the value of the element
    */
  def success[B](value: B): SuccessElem[B] =
    SuccessElem(ctx, tpe, id, rev, instant, offset, value)

  /**
    * Produces a new [[DroppedElem]] copying the common properties.
    */
  def dropped: DroppedElem =
    DroppedElem(ctx, tpe, id, rev, instant, offset)
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
  final case class SuccessElem[+A](
      ctx: ElemCtx,
      tpe: EntityType,
      id: Iri,
      rev: Int,
      instant: Instant,
      offset: Offset,
      value: A
  ) extends Elem[A]

  /**
    * An element that has suffered a processing failure.
    * @param ctx
    *   the element contextual information
    * @param reason
    *   a human readable reason for why processing has failed for this element
    */
  final case class FailedElem(
      ctx: ElemCtx,
      tpe: EntityType,
      id: Iri,
      rev: Int,
      instant: Instant,
      offset: Offset,
      reason: String
  ) extends Elem[Nothing]

  /**
    * An element that was discarded through filtering.
    * @param ctx
    *   the element contextual information
    */
  final case class DroppedElem(ctx: ElemCtx, tpe: EntityType, id: Iri, rev: Int, instant: Instant, offset: Offset)
      extends Elem[Nothing]
}
