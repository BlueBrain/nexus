package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import java.time.Instant
import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet, NonEmptyVector}
import cats.implicits._
import cats.kernel.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor.className
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure.KeyMissingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, ExpandedJsonLdCursor}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

trait JsonLdDecoder[A] { self =>

  /**
    * Converts a [[ExpandedJsonLdCursor]] to ''A''.
    *
    * @param cursor the cursor from the [[ExpandedJsonLd]] document
    */
  def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, A]

  /**
    * Converts a [[ExpandedJsonLd]] document to ''A''.
    */
  def apply(expanded: ExpandedJsonLd): Either[JsonLdDecoderError, A] =
    apply(ExpandedJsonLdCursor(expanded))

  /**
    * Map a function over this [[JsonLdDecoder]].
    */
  final def map[B](f: A => B): JsonLdDecoder[B] = new JsonLdDecoder[B] {
    override def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, B] = self(cursor).map(f)
  }

  /**
    * FlatMap a function over this [[JsonLdDecoder]].
    */
  final def flatMap[B](f: A => Either[JsonLdDecoderError, B]): JsonLdDecoder[B] = new JsonLdDecoder[B] {
    override def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, B] = self(cursor).flatMap(f)
  }

  /**
    * Choose the first succeeding decoder.
    */
  final def or[AA >: A](other: => JsonLdDecoder[AA]): JsonLdDecoder[AA] = new JsonLdDecoder[AA] {
    override def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, AA] =
      self(cursor) orElse other(cursor)
  }

}

object JsonLdDecoder {
  implicit val iriJsonLdDecoder: JsonLdDecoder[Iri]         = _.get[Iri](keywords.id)
  implicit val bNodeJsonLdDecoder: JsonLdDecoder[BNode]     = _ => Right(BNode.random)
  implicit val iOrBJsonLdDecoder: JsonLdDecoder[IriOrBNode] =
    iriJsonLdDecoder or bNodeJsonLdDecoder.map[IriOrBNode](identity)

  implicit val stringJsonLdDecoder: JsonLdDecoder[String]   = _.get[String](keywords.value)
  implicit val intJsonLdDecoder: JsonLdDecoder[Int]         = _.getOr(keywords.value, _.toIntOption)
  implicit val longJsonLdDecoder: JsonLdDecoder[Long]       = _.getOr(keywords.value, _.toLongOption)
  implicit val doubleJsonLdDecoder: JsonLdDecoder[Double]   = _.getOr(keywords.value, _.toDoubleOption)
  implicit val floatJsonLdDecoder: JsonLdDecoder[Float]     = _.getOr(keywords.value, _.toFloatOption)
  implicit val booleanJsonLdDecoder: JsonLdDecoder[Boolean] = _.getOr(keywords.value, _.toBooleanOption)

  implicit val instantJsonLdDecoder: JsonLdDecoder[Instant]                    = _.getValueTry(Instant.parse)
  implicit val uuidJsonLdDecoder: JsonLdDecoder[UUID]                          = _.getValueTry(UUID.fromString)
  implicit val durationJsonLdDecoder: JsonLdDecoder[Duration]                  = _.getValueTry(Duration.apply)
  implicit val finiteDurationJsonLdDecoder: JsonLdDecoder[FiniteDuration]      =
    _.getValue(str => Try(Duration(str)).toOption.collectFirst { case f: FiniteDuration => f })

  implicit def vectorJsonLdDecoder[A: JsonLdDecoder]: JsonLdDecoder[Vector[A]] = listJsonLdDecoder[A].map(_.toVector)

  implicit def nonEmptyVectorJsonLdDecoder[A: JsonLdDecoder: ClassTag]: JsonLdDecoder[NonEmptyVector[A]] =
    nonEmptyListJsonLdDecoder[A].map(_.toNev)

  implicit def nonEmptySetJsonLdDecoder[A: JsonLdDecoder: ClassTag: Order]: JsonLdDecoder[NonEmptySet[A]] =
    setJsonLdDecoder[A].flatMap { s =>
      s.toList match {
        case head :: rest => Right(NonEmptySet.of(head, rest: _*))
        case _            => Left(ParsingFailure(s"Expected a NonEmptySet[${className[A]}], but the current set is empty"))
      }
    }

  implicit def nonEmptyListJsonLdDecoder[A: JsonLdDecoder: ClassTag]: JsonLdDecoder[NonEmptyList[A]] =
    listJsonLdDecoder[A].flatMap {
      case head :: rest => Right(NonEmptyList(head, rest))
      case _            => Left(ParsingFailure(s"Expected a NonEmptyList[${className[A]}], but the current list is empty"))
    }

  implicit def setJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Set[A]] =
    cursor => cursor.values.flatMap(innerCursors => innerCursors.traverse(dec(_))).map(_.toSet)

  implicit def listJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[List[A]] =
    cursor => cursor.downList.values.flatMap(innerCursors => innerCursors.traverse(dec(_)))

  implicit def optionJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Option[A]] =
    cursor =>
      if (cursor.succeeded) dec(cursor).map(Some.apply).recover { case _: KeyMissingFailure =>
        None
      }
      else Right(None)

}
