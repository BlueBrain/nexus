package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import cats.data.NonEmptyList
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure.KeyMissingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, ExpandedJsonLdCursor}
import io.circe.parser.parse
import io.circe.{Json, JsonObject}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

trait JsonLdDecoder[A] { self =>

  /**
    * Converts a [[ExpandedJsonLdCursor]] to ''A''.
    *
    * @param cursor
    *   the cursor from the [[ExpandedJsonLd]] document
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

  /**
    * @tparam AA
    *   the value supertype
    * @return
    *   a new decoder for a supertype of A
    */
  final def covary[AA >: A]: JsonLdDecoder[AA] =
    map(identity)

  /**
    * Chains a new decoder that uses this cursor and the decoded value to attempt to produce a new value. The cursor
    * retains its history for providing better error messages.
    *
    * @param f
    *   the function that continues the decoding
    */
  final def andThen[B](f: (ExpandedJsonLdCursor, A) => Either[JsonLdDecoderError, B]): JsonLdDecoder[B] =
    new JsonLdDecoder[B] {
      override def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, B] = {
        self(cursor) match {
          case Left(err)    => Left(err)
          case Right(value) => f(cursor, value)
        }
      }
    }

}

object JsonLdDecoder {

  def apply[A](implicit A: JsonLdDecoder[A]): JsonLdDecoder[A] = A

  private val relativeOrAbsoluteIriDecoder: JsonLdDecoder[Iri] = _.get[Iri](keywords.id)

  implicit val iriJsonLdDecoder: JsonLdDecoder[Iri] =
    relativeOrAbsoluteIriDecoder.andThen { (cursor, iri) =>
      if (iri.isAbsolute) Right(iri)
      else Left(ParsingFailure("AbsoluteIri", iri.toString, cursor.history))
    }

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

  implicit def setJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Set[A]] =
    cursor => cursor.values.flatMap(innerCursors => innerCursors.traverse(dec(_))).map(_.toSet)

  implicit def listJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[List[A]] =
    cursor => cursor.downList.values.flatMap(innerCursors => innerCursors.traverse(dec(_)))

  implicit def nonEmptyListsonLdDecoder[A: JsonLdDecoder](implicit A: ClassTag[A]): JsonLdDecoder[NonEmptyList[A]] =
    listJsonLdDecoder[A].flatMap {
      case Nil          => Left(ParsingFailure(s"Expected a NonEmptyList[${A.simpleName}], but the current list is empty"))
      case head :: tail => Right(NonEmptyList(head, tail))
    }

  implicit def optionJsonLdDecoder[A](implicit dec: JsonLdDecoder[A]): JsonLdDecoder[Option[A]] =
    cursor =>
      if (cursor.succeeded) dec(cursor).map(Some.apply).recover {
        case k: KeyMissingFailure if k.path.isEmpty =>
          None
      }
      else Right(None)

  // assumes the field is encoded as a string
  // TODO: remove when `@type: json` is supported by the json-ld lib
  implicit val jsonObjectJsonLdDecoder: JsonLdDecoder[JsonObject] = _.getValue(parse(_).toOption.flatMap(_.asObject))

  // assumes the field is encoded as a string
  // TODO: remove when `@type: json` is supported by the json-ld lib
  implicit val jsonJsonLdDecoder: JsonLdDecoder[Json] = _.getValue(parse(_).toOption)

  implicit val unitJsonLdDecoder: JsonLdDecoder[Unit] = _ => Right(())
}
