package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import java.time.Instant
import java.util.UUID

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor.className
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.DecodingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, ExpandedJsonLdCursor}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

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
  implicit val iriJsonLdDecoder: JsonLdDecoder[Iri]         = _.getId
  implicit val bNodeJsonLdDecoder: JsonLdDecoder[BNode]     = _ => Right(BNode.random)
  implicit val iOrBJsonLdDecoder: JsonLdDecoder[IriOrBNode] =
    iriJsonLdDecoder or bNodeJsonLdDecoder.map[IriOrBNode](identity)

  implicit val stringJsonLdDecoder: JsonLdDecoder[String]   = _.getString
  implicit val intJsonLdDecoder: JsonLdDecoder[Int]         = _.getInt
  implicit val longJsonLdDecoder: JsonLdDecoder[Long]       = _.getLong
  implicit val doubleJsonLdDecoder: JsonLdDecoder[Double]   = _.getDouble
  implicit val floatJsonLdDecoder: JsonLdDecoder[Float]     = _.getFloat
  implicit val booleanJsonLdDecoder: JsonLdDecoder[Boolean] = _.getBoolean

  implicit val uuidJsonLdDecoder: JsonLdDecoder[UUID]                     = _.getUUID
  implicit val durationJsonLdDecoder: JsonLdDecoder[Duration]             = _.getDuration
  implicit val finiteDurationJsonLdDecoder: JsonLdDecoder[FiniteDuration] = _.getFiniteDuration
  implicit val instantJsonLdDecoder: JsonLdDecoder[Instant]               = _.getInstant

  implicit def vectorJsonLdDecoder[A: JsonLdDecoder]: JsonLdDecoder[Vector[A]] = listJsonLdDecoder[A].map(_.toVector)

  implicit def nonEmptyVectorJsonLdDecoder[A: JsonLdDecoder: ClassTag]: JsonLdDecoder[NonEmptyVector[A]] =
    nonEmptyListJsonLdDecoder[A].map(_.toNev)

  implicit def nonEmptyListJsonLdDecoder[A: JsonLdDecoder: ClassTag]: JsonLdDecoder[NonEmptyList[A]] =
    listJsonLdDecoder[A].flatMap {
      case head :: rest => Right(NonEmptyList(head, rest))
      case _            => Left(DecodingFailure(s"Expected a NonEmptyList[${className[A]}], but the current list is empty", None))
    }

  implicit def setJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Set[A]] =
    cursor => cursor.values.flatMap(innerCursors => innerCursors.traverse(dec(_))).map(_.toSet)

  implicit def listJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[List[A]] =
    cursor => cursor.downList.values.flatMap(innerCursors => innerCursors.traverse(dec(_)))

  implicit def optionJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Option[A]] =
    cursor => if (cursor.succeeded) dec(cursor).map(Some.apply) else Right(None)

}
