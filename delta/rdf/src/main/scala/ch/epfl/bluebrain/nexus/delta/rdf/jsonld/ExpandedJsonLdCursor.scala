package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import java.time.Instant
import java.util.UUID

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.DecodingFailure
import io.circe.CursorOp._
import io.circe.{ACursor, Decoder, Json}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

/**
  * A cursor for an [[ExpandedJsonLd]] document which relies on the underlying circe cursor
  */
final class ExpandedJsonLdCursor private (value: ACursor) {

  /**
    * Indicate whether this cursor represents the result of a successful operation.
    */
  def succeeded: Boolean = value.succeeded

  /**
    * Moves the focus down the passed ''property''.
    */
  def downField(property: Iri): ExpandedJsonLdCursor =
    new ExpandedJsonLdCursor(value.downArray.downField(property.toString))

  /**
    * Moves the focus down the @list property
    */
  def downList: ExpandedJsonLdCursor =
    new ExpandedJsonLdCursor(value.downArray.downField(keywords.list))

  /**
    * If the focus is a Json Array, return each of its individual focus
    * @return
    */
  def values: Either[DecodingFailure, List[ExpandedJsonLdCursor]] =
    value.values match {
      case Some(jsons) => Right(jsons.toList.map(json => new ExpandedJsonLdCursor(Json.arr(json).hcursor)))
      case None        => Left(DecodingFailure("Sequence", value.history))
    }

  /**
    * Get the set of types from the current cursor.
    */
  def getTypes: Either[DecodingFailure, Set[Iri]] =
    value.downArray
      .downField(keywords.tpe)
      .as[Set[Iri]]
      .leftMap(err => DecodingFailure("Set[Iri]", err.history))

  /**
    * Get the @id [[Iri]] from the current cursor.
    */
  def getId: Either[DecodingFailure, Iri] =
    get[Iri](keywords.id)

  /**
    * Get a [[String]] @value from the current cursor.
    */
  def getString: Either[DecodingFailure, String] =
    get[String](keywords.value)

  /**
    * Get a [[Boolean]] @value from the current cursor.
    */
  def getBoolean: Either[DecodingFailure, Boolean] =
    get[Boolean](keywords.value) orElse getValue(_.toBooleanOption)

  /**
    * Get an [[Int]] @value from the current cursor.
    */
  def getInt: Either[DecodingFailure, Int] =
    get[Int](keywords.value) orElse getValue(_.toIntOption)

  /**
    * Get a [[Long]] @value from the current cursor.
    */
  def getLong: Either[DecodingFailure, Long] =
    get[Long](keywords.value) orElse getValue(_.toLongOption)

  /**
    * Get a [[Double]] @value from the current cursor.
    */
  def getDouble: Either[DecodingFailure, Double] =
    get[Double](keywords.value) orElse getValue(_.toDoubleOption)

  /**
    * Get a [[Float]] @value from the current cursor.
    */
  def getFloat: Either[DecodingFailure, Float] =
    get[Float](keywords.value) orElse getValue(_.toFloatOption)

  /**
    * Get a [[UUID]] @value from the current cursor.
    */
  def getUUID: Either[DecodingFailure, UUID] =
    getValue(str => Try(UUID.fromString(str)).toOption)

  /**
    * Get a [[Duration]] @value from the current cursor.
    */
  def getDuration: Either[DecodingFailure, Duration] =
    getValue(str => Try(Duration(str)).toOption)

  /**
    * Get a [[FiniteDuration]] @value from the current cursor.
    */
  def getFiniteDuration: Either[DecodingFailure, FiniteDuration] =
    getValue(str => Try(Duration(str)).toOption.collectFirst { case f: FiniteDuration => f })

  /**
    * Get a [[Instant]] @value from the current cursor.
    */
  def getInstant: Either[DecodingFailure, Instant]               =
    getValue(str => Try(Instant.parse(str)).toOption)

  private def getValue[A: ClassTag](toValue: String => Option[A]): Either[DecodingFailure, A] =
    get[String](keywords.value).flatMap { str =>
      toValue(str).toRight(
        DecodingFailure(className[A], str, DownField(keywords.value) :: DownArray :: value.history)
      )
    }

  private def get[A: Decoder: ClassTag](key: String): Either[DecodingFailure, A] =
    value.downArray.get[A](key).leftMap(err => DecodingFailure(className[A], err.history))

}

object ExpandedJsonLdCursor {

  /**
    * Construct a [[ExpandedJsonLdCursor]] from an [[ExpandedJsonLd]]
    */
  final def apply(expanded: ExpandedJsonLd): ExpandedJsonLdCursor =
    new ExpandedJsonLdCursor(expanded.json.hcursor)

  private[jsonld] def className[A](implicit A: ClassTag[A]) = A.runtimeClass.getSimpleName
}
