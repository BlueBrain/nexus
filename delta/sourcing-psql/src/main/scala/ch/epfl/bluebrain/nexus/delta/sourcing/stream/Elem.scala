package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.{Applicative, Eval, Traverse}
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
    *   the underlying entity type
    */
  def tpe: EntityType

  /**
    * @return
    *   the underlying entity id
    */
  def id: String

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
    * Produces a new [[FailedElem]] with the provided reason copying the common properties
    * @param throwable
    *   the error why the element processing failed
    */
  def failed(throwable: Throwable): FailedElem = FailedElem(tpe, id, instant, offset, throwable)

  /**
    * Produces a new [[SuccessElem]] with the provided value copying the common properties.
    * @param value
    *   the value of the element
    */
  def success[B](value: B): SuccessElem[B] = SuccessElem(tpe, id, instant, offset, value)

  /**
    * Produces a new [[DroppedElem]] copying the common properties.
    */
  def dropped: DroppedElem = DroppedElem(tpe, id, instant, offset)

  /**
    * Maps the underlying element value if this is a [[Elem.SuccessElem]] using f.
    * @param f
    *   the mapping function
    */
  def map[B](f: A => B): Elem[B] = this match {
    case e: SuccessElem[A] => e.copy(value = f(e.value))
    case e: FailedElem     => e
    case e: DroppedElem    => e
  }

  /**
    * Discard the underlying element value if present.
    */
  def void: Elem[Unit] =
    map(_ => ())
}

object Elem {

  /**
    * An element that has a value of type [[A]] that has been previously successfully processed.
    * @param tpe
    *   the underlying entity type
    * @param id
    *   the underlying entity id
    * @param instant
    *   the instant when the element was produced
    * @param offset
    *   the element offset
    * @param value
    *   the element value
    * @tparam A
    *   the value type of the element
    */
  final case class SuccessElem[+A](
      tpe: EntityType,
      id: String,
      instant: Instant,
      offset: Offset,
      value: A
  ) extends Elem[A]

  object SuccessElem {
    def apply[A](tpe: EntityType,
                 id: Iri,
                 instant: Instant,
                 offset: Offset,
                 value: A): SuccessElem[A] =
      SuccessElem(tpe, id.toString, instant, offset, value)
  }

  /**
    * An element that has suffered a processing failure.
    * @param tpe
    *   the underlying entity type
    * @param id
    *   the underlying entity id
    * @param instant
    *   the instant when the element was produced
    * @param offset
    *   the element offset
    * @param throwable
    *   the error responsible for this element to fail
    */
  final case class FailedElem(
      tpe: EntityType,
      id: String,
      instant: Instant,
      offset: Offset,
      throwable: Throwable
  ) extends Elem[Nothing]

  /**
    * An element that was discarded through filtering.
    * @param ctx
    *   the element contextual information
    * @param tpe
    *   the underlying entity type
    * @param id
    *   the underlying entity id
    * @param rev
    *   the underlying entity revision
    * @param instant
    *   the instant when the element was produced
    * @param offset
    *   the element offset
    */
  final case class DroppedElem(
      tpe: EntityType,
      id: String,
      instant: Instant,
      offset: Offset
  ) extends Elem[Nothing]

  implicit val traverseElem: Traverse[Elem] = new Traverse[Elem] {
    override def traverse[G[_]: Applicative, A, B](fa: Elem[A])(f: A => G[B]): G[Elem[B]] =
      fa match {
        case s: SuccessElem[A] => Applicative[G].map(f(s.value))(s.success)
        case dropped: DroppedElem    => Applicative[G].pure(dropped)
        case failed: FailedElem    => Applicative[G].pure(failed)
      }

    override def foldLeft[A, B](fa: Elem[A], b: B)(f: (B, A) => B): B =
      fa match {
        case s: SuccessElem[A] => f(b, s.value)
        case _ => b
      }

    override def foldRight[A, B](fa: Elem[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa match {
        case s: SuccessElem[A] => f(s.value, lb)
        case _    => lb
      }
  }
}
