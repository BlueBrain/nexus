package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.{Applicative, Eval, Traverse}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import monix.bio.{Task, UIO}

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
  def id: Iri

  /**
    * @return
    *   the underlying project if there is one
    */
  def project: Option[ProjectRef]

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
    * @return
    *   the revision number
    */
  def revision: Int

  /**
    * Produces a new [[FailedElem]] with the provided reason copying the common properties
    * @param throwable
    *   the error why the element processing failed
    */
  def failed(throwable: Throwable): FailedElem = FailedElem(tpe, id, project, instant, offset, throwable, revision)

  /**
    * Produces a new [[SuccessElem]] with the provided value copying the common properties.
    * @param value
    *   the value of the element
    */
  def success[B](value: B): SuccessElem[B] = SuccessElem(tpe, id, project, instant, offset, value, revision)

  /**
    * Produces a new [[DroppedElem]] copying the common properties.
    */
  def dropped: DroppedElem = DroppedElem(tpe, id, project, instant, offset, revision)

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
    * Maps the underlying element value if this is a [[Elem.SuccessElem]] using f and marks the element as failed if it
    * returns a left
    * @param f
    *   the mapping function
    */
  def attempt[B](f: A => Either[Throwable, B]): Elem[B] = this match {
    case e: SuccessElem[A] => f(e.value).fold(e.failed, e.success)
    case e: FailedElem     => e
    case e: DroppedElem    => e
  }

  /**
    * Like `[[Elem#map]]`, but accepts a function returning a [[Task]]. If the task failed, the [[Elem.SuccessElem]]
    * will become a [[Elem.FailedElem]]
    * @param f
    *   the mapping function
    */
  def evalMap[B](f: A => Task[B]): UIO[Elem[B]] = this match {
    case e: SuccessElem[A] =>
      f(e.value).redeemCause(
        c => e.failed(c.toThrowable),
        e.success
      )
    case e: FailedElem     => UIO.pure(e)
    case e: DroppedElem    => UIO.pure(e)
  }

  /**
    * Effectfully maps and filters the elem depending on the optionality of the result of the application of the
    * effectful function `f`.
    */
  def evalMapFilter[B](f: A => Task[Option[B]]): UIO[Elem[B]] = this match {
    case e: SuccessElem[A] =>
      f(e.value).redeem(
        e.failed,
        {
          case Some(v) => e.success(v)
          case None    => e.dropped
        }
      )
    case e: FailedElem     => UIO.pure(e)
    case e: DroppedElem    => UIO.pure(e)
  }

  /**
    * Discard the underlying element value if present.
    */
  def void: Elem[Unit] =
    map(_ => ())

  override def toString: String = s"${project.fold("")(_.toString)}/$id:$revision"
}

object Elem {

  /**
    * Builds an [[Elem]] instance out of an [[Either]]
    * @param tpe
    *   the entity type
    * @param id
    *   the identifier
    * @param project
    *   the project
    * @param instant
    *   the instant
    * @param offset
    *   the offset
    * @param either
    *   the error/value
    * @param rev
    *   the revision
    */
  def fromEither[A](
      tpe: EntityType,
      id: Iri,
      project: Option[ProjectRef],
      instant: Instant,
      offset: Offset,
      either: Either[Throwable, A],
      rev: Int
  ): Elem[A] =
    either.fold(
      err => FailedElem(tpe, id, project, instant, offset, err, rev),
      restart => SuccessElem(tpe, id, project, instant, offset, restart, rev)
    )

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
      id: Iri,
      project: Option[ProjectRef],
      instant: Instant,
      offset: Offset,
      value: A,
      revision: Int
  ) extends Elem[A]

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
      id: Iri,
      project: Option[ProjectRef],
      instant: Instant,
      offset: Offset,
      throwable: Throwable,
      revision: Int
  ) extends Elem[Nothing]

  /**
    * An element that was discarded through filtering.
    * @param tpe
    *   the underlying entity type
    * @param id
    *   the underlying entity id
    * @param instant
    *   the instant when the element was produced
    * @param offset
    *   the element offset
    */
  final case class DroppedElem(
      tpe: EntityType,
      id: Iri,
      project: Option[ProjectRef],
      instant: Instant,
      offset: Offset,
      revision: Int
  ) extends Elem[Nothing]

  implicit val traverseElem: Traverse[Elem] = new Traverse[Elem] {
    override def traverse[G[_]: Applicative, A, B](fa: Elem[A])(f: A => G[B]): G[Elem[B]] =
      fa match {
        case s: SuccessElem[A]    => Applicative[G].map(f(s.value))(s.success)
        case dropped: DroppedElem => Applicative[G].pure(dropped)
        case failed: FailedElem   => Applicative[G].pure(failed)
      }

    override def foldLeft[A, B](fa: Elem[A], b: B)(f: (B, A) => B): B =
      fa match {
        case s: SuccessElem[A] => f(b, s.value)
        case _                 => b
      }

    override def foldRight[A, B](fa: Elem[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa match {
        case s: SuccessElem[A] => f(s.value, lb)
        case _                 => lb
      }
  }
}
