package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure.KeyMissingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.{DecodingFailure, ParsingFailure}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, JsonLdDecoderError}
import io.circe.CursorOp._
import io.circe.{ACursor, Decoder, Json}

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
      case None        => Left(ParsingFailure("Sequence", value.history))
    }

  /**
    * Attempt to decode the current cursor using the implicitly available [[JsonLdDecoder]]
    */
  def get[A](implicit decoder: JsonLdDecoder[A]): Either[JsonLdDecoderError, A] =
    decoder(this)

  /**
    * Attempt to decode the current cursor using the implicitly available [[JsonLdDecoder]] and
    * if the focus did not succeed return the passed ''default''
    *
    * @param default the value returned when the focus did not succed on the current cursor
    */
  def getOrElse[A](default: => A)(implicit decoder: JsonLdDecoder[A]): Either[JsonLdDecoderError, A] =
    if (succeeded) decoder(this) else Right(default)

  /**
    * Get the set of types from the current cursor.
    */
  def getTypes: Either[DecodingFailure, Set[Iri]] =
    value.downArray
      .downField(keywords.tpe)
      .as[Set[Iri]]
      .leftMap(err => ParsingFailure("Set[Iri]", err.history))

  /**
    * Gets the @value field as a String and then attempts to convert it to [[A]] using the function ''toValue''
    */
  def getValueTry[A: ClassTag](toValue: String => A): Either[DecodingFailure, A] =
    getValue(v => Try(toValue(v)).toOption)

  /**
    * Gets the @value field as a String and then attempts to convert it to [[A]] using the function ''toValue''
    */
  def getValue[A: ClassTag](toValue: String => Option[A]): Either[DecodingFailure, A] =
    get[String](keywords.value).flatMap { str =>
      toValue(str).toRight(
        ParsingFailure(className[A], str, DownField(keywords.value) :: DownArray :: value.history)
      )
    }

  private[jsonld] def get[A: Decoder: ClassTag](key: String): Either[DecodingFailure, A] =
    value.downArray.get[Option[A]](key).leftMap(err => ParsingFailure(className[A], err.history)).flatMap {
      case Some(s) => Right(s)
      case None    => Left(KeyMissingFailure(key, value.history))
    }

  private[jsonld] def getOr[A: Decoder: ClassTag](
      key: String,
      toValue: String => Option[A]
  ): Either[DecodingFailure, A] =
    get[A](key) orElse getValue(toValue)

}

object ExpandedJsonLdCursor {

  /**
    * Construct a [[ExpandedJsonLdCursor]] from an [[ExpandedJsonLd]]
    */
  final def apply(expanded: ExpandedJsonLd): ExpandedJsonLdCursor =
    new ExpandedJsonLdCursor(expanded.json.hcursor)

  private[jsonld] def className[A](implicit A: ClassTag[A]) = A.runtimeClass.getSimpleName
}
