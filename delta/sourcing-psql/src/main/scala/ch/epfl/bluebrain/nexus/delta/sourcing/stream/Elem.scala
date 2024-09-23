package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import cats.implicits.{toFoldableOps, toFunctorOps, toTraverseOps}
import cats.{Applicative, Eval, Traverse}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import doobie.Read
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Decoder}

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
  def rev: Int

  /**
    * Produces a new [[FailedElem]] with the provided reason copying the common properties
    * @param throwable
    *   the error why the element processing failed
    */
  def failed(throwable: Throwable): FailedElem =
    FailedElem(tpe, id, project, instant, offset, FailureReason(throwable), rev)

  /**
    * Produces a new [[FailedElem]] with the provided reason copying the common properties
    * @param reason
    *   the reason why the element processing failed
    */
  def failed(reason: FailureReason): FailedElem = FailedElem(tpe, id, project, instant, offset, reason, rev)

  /**
    * Produces a new [[SuccessElem]] with the provided value copying the common properties.
    * @param value
    *   the value of the element
    */
  def success[B](value: B): SuccessElem[B] = SuccessElem(tpe, id, project, instant, offset, value, rev)

  /**
    * Produces a new [[DroppedElem]] copying the common properties.
    */
  def dropped: DroppedElem = DroppedElem(tpe, id, project, instant, offset, rev)

  /** Action of dropping an Elem */
  def drop: Elem[Nothing] = this match {
    case e: SuccessElem[A] => e.dropped
    case e: FailedElem     => e
    case e: DroppedElem    => e
  }

  /**
    * Maps the underlying element value if this is a [[Elem.SuccessElem]] using f.
    * @param f
    *   the mapping function
    */
  def map[B](f: A => B): Elem[B] = this match {
    case s: SuccessElem[A] => s.mapValue(f)
    case f: FailedElem     => f
    case d: DroppedElem    => d
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
  def evalMap[B](f: A => IO[B]): IO[Elem[B]] = this match {
    case e: SuccessElem[A] =>
      f(e.value).redeem(c => e.failed(c), e.success)
    case e: FailedElem     => IO.pure(e)
    case e: DroppedElem    => IO.pure(e)
  }

  /**
    * Effectfully maps and filters the elem depending on the optionality of the result of the application of the
    * effectful function `f`.
    */
  def evalMapFilter[B](f: A => IO[Option[B]]): IO[Elem[B]] = this match {
    case e: SuccessElem[A] =>
      f(e.value).redeem(
        e.failed,
        {
          case Some(v) => e.success(v)
          case None    => e.dropped
        }
      )
    case e: FailedElem     => IO.pure(e)
    case e: DroppedElem    => IO.pure(e)
  }

  /**
    * Discard the underlying element value if present.
    */
  def void: Elem[Unit] =
    map(_ => ())

  /**
    * Returns the value as an option
    */
  def toOption: Option[A] = this match {
    case e: SuccessElem[A] => Some(e.value)
    case _: FailedElem     => None
    case _: DroppedElem    => None
  }

  override def toString: String =
    s"${this.getClass.getSimpleName}[${project.fold("")(_.toString)}/$id:$rev]{${offset.value}}"
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
      err => FailedElem(tpe, id, project, instant, offset, FailureReason(err), rev),
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
      rev: Int
  ) extends Elem[A] {
    def mapValue[B](f: A => B): Elem.SuccessElem[B] = copy(value = f(value))

    def withProject(project: ProjectRef): Elem.SuccessElem[A] = this.copy(project = Some(project))
  }

  object SuccessElem {
    implicit def read[Value](implicit s: Decoder[Value]): Read[SuccessElem[Value]] = {
      import doobie._
      import doobie.postgres.implicits._
      import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
      implicit val v: Get[Value] = pgDecoderGetT[Value]
      Read[(EntityType, Iri, Value, Int, Instant, Long, String, String)].map {
        case (tpe, id, value, rev, instant, offset, org, proj) =>
          SuccessElem(tpe, id, Some(ProjectRef.unsafe(org, proj)), instant, Offset.at(offset), value, rev)
      }
    }

    implicit val traverse: Traverse[SuccessElem] = new Traverse[SuccessElem] {
      override def traverse[G[_]: Applicative, A, B](s: SuccessElem[A])(f: A => G[B]): G[SuccessElem[B]] =
        Applicative[G].map(f(s.value))(v => s.copy(value = v))

      override def foldLeft[A, B](s: SuccessElem[A], b: B)(f: (B, A) => B): B = f(b, s.value)

      override def foldRight[A, B](s: SuccessElem[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = f(s.value, lb)
    }
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
      id: Iri,
      project: Option[ProjectRef],
      instant: Instant,
      offset: Offset,
      reason: FailureReason,
      rev: Int
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
      rev: Int
  ) extends Elem[Nothing]

  implicit val traverseElem: Traverse[Elem] = new Traverse[Elem] {
    override def traverse[G[_]: Applicative, A, B](fa: Elem[A])(f: A => G[B]): G[Elem[B]] =
      fa match {
        case s: SuccessElem[A]    => s.traverse(f).widen[Elem[B]]
        case dropped: DroppedElem => Applicative[G].pure(dropped)
        case failed: FailedElem   => Applicative[G].pure(failed)
      }

    override def foldLeft[A, B](fa: Elem[A], b: B)(f: (B, A) => B): B =
      fa match {
        case s: SuccessElem[A] => s.foldLeft(b)(f)
        case _                 => b
      }

    override def foldRight[A, B](fa: Elem[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa match {
        case s: SuccessElem[A] => s.foldRight(lb)(f)
        case _                 => lb
      }
  }

  implicit private val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)

  implicit val elemUnitEncoder: Codec.AsObject[Elem[Unit]] = deriveConfiguredCodec[Elem[Unit]]

}
