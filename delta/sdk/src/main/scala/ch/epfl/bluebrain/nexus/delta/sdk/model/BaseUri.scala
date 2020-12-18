package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.{IllegalAbsoluteIRIFormatError, IllegalLabelFormatError}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Encoder}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.annotation.tailrec
import scala.util.Try

/**
  * The BaseUri holds information about the platform endpoint.
  *
  * @param base   the base [[Uri]]
  * @param prefix an optional path prefix to be appended to the ''base''
  */
final case class BaseUri private (base: Uri, prefix: Option[Label]) {

  /**
    * Formats the prefix segment
    */
  val prefixSegment: String = prefix.fold("")(p => s"/$p")

  /**
    * The platform endpoint with base / prefix
    */
  val endpoint: Uri = prefix.fold(base)(p => base / p.value)

  /**
    * The platform endpoint with base / prefix
    */
  val iriEndpoint: Iri = endpoint.toIri

  /**
    * Scheme of the underlying uri
    */
  def scheme: String = base.scheme

  /**
    * Authority of the underlying uri
    */
  def authority: Uri.Authority = base.authority

  override def toString: String = endpoint.toString
}

object BaseUri {

  /**
    * Construct a [[BaseUri]] without a prefix.
    */
  def withoutPrefix(base: Uri): BaseUri = new BaseUri(base, None)

  /**
    * Construct a [[BaseUri]] from an [[Uri]]
    */
  def apply(base: Uri): Either[FormatError, BaseUri] = {
    @tailrec
    def rec(uri: Uri, consumed: Path, remaining: Path): Either[FormatError, BaseUri] = remaining match {
      case Path.Empty                                              => Right(BaseUri.withoutPrefix(uri.withPath(consumed)))
      case Path.Slash(tail)                                        => rec(uri, consumed, tail)
      case Path.Segment(head, Path.Slash(Path.Empty) | Path.Empty) =>
        Label(head)
          .map(label => BaseUri(uri.withPath(consumed).withoutFragment.copy(rawQueryString = None), Some(label)))
      case Path.Segment(head, Path.Slash(Path.Slash(other)))       =>
        rec(uri, consumed, Path.Segment(head, Path.Slash(other)))
      case Path.Segment(head, Path.Slash(other))                   =>
        rec(uri, consumed ?/ head, other)
    }
    if (base.isAbsolute) rec(base, Path.Empty, base.path)
    else Left(IllegalAbsoluteIRIFormatError(base.toString))
  }

  /**
    * Construct a [[BaseUri]] with a prefix.
    */
  def apply(base: Uri, prefix: Label): BaseUri = new BaseUri(base, Some(prefix))

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] = {

    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .flatMap(BaseUri(_).leftMap {
          case IllegalAbsoluteIRIFormatError(iri)  =>
            CannotConvert(iri, classOf[Uri].getSimpleName, "The value must be an absolute Uri.")
          case IllegalLabelFormatError(label, err) =>
            CannotConvert(label, classOf[Label].getSimpleName, err.getOrElse(""))
          case _                                   =>
            CannotConvert(str, classOf[Uri].getSimpleName, "Unexpected error")
        })
    )
  }

  implicit val baseUriEncoder: Encoder[BaseUri]                   = Encoder.encodeString.contramap(_.toString)
  implicit val baseUriDecoder: Decoder[BaseUri]                   = Decoder.decodeString.emap(apply(_).leftMap(_.toString))
  implicit final val baseUriJsonLdDecoder: JsonLdDecoder[BaseUri] = _.getValue(apply(_).toOption)
}
