package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.instances.UriInstances
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.IllegalAbsoluteIRIFormatError
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label.IllegalLabelFormat
import io.circe.{Decoder, Encoder}
import org.http4s.{Query, Uri}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.module.http4s.uriReader

/**
  * The BaseUri holds information about the platform endpoint.
  *
  * @param base
  *   the base [[Uri]]
  * @param prefix
  *   an optional path prefix to be appended to the ''base''
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
  def scheme: Option[Uri.Scheme] = base.scheme

  /**
    * Authority of the underlying uri
    */
  def authority: Option[Uri.Authority] = base.authority

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
  def apply(uri: Uri): Either[FormatError, BaseUri] = {
    if (uri.path.isEmpty || uri.path.absolute) {
      val normalized = uri.path.normalize
      normalized.segments.lastOption match {
        case Some(segment) =>
          val base = uri.withoutFragment.copy(
            path = Uri.Path.Root.concat(Uri.Path(normalized.segments.dropRight(1))),
            query = Query.empty
          )
          Label(segment.toString).map(BaseUri(base, _))
        case None          =>
          val base = uri.withoutFragment.copy(
            path = Uri.Path.empty,
            query = Query.empty
          )
          Right(BaseUri.withoutPrefix(base))
      }
    } else Left(IllegalAbsoluteIRIFormatError(uri.toString))
  }

  /**
    * Construct a [[BaseUri]] with a prefix.
    */
  def apply(base: Uri, prefix: Label): BaseUri = new BaseUri(base, Some(prefix))

  def unsafe(base: String, prefix: String): BaseUri = BaseUri(Uri.unsafeFromString(base), Label.unsafe(prefix))

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] =
    uriReader.emap { uri =>
      BaseUri(uri).leftMap {
        case IllegalAbsoluteIRIFormatError(iri) =>
          CannotConvert(iri, classOf[Uri].getSimpleName, "The value must be an absolute Uri.")
        case IllegalLabelFormat(label, err)     =>
          CannotConvert(label, classOf[Label].getSimpleName, err.getOrElse(""))
        case _                                  =>
          CannotConvert(uri.toString(), classOf[Uri].getSimpleName, "Unexpected error")
      }
    }

  implicit val baseUriEncoder: Encoder[BaseUri]                   = Encoder.encodeString.contramap(_.toString)
  implicit val baseUriDecoder: Decoder[BaseUri]                   = UriInstances.uriDecoder.emap(apply(_).leftMap(_.toString))
  implicit final val baseUriJsonLdDecoder: JsonLdDecoder[BaseUri] = _.getValue { s =>
    Uri.fromString(s).flatMap(apply).toOption
  }
}
