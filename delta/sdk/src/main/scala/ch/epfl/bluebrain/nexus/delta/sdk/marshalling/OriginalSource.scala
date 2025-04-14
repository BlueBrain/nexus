package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import cats.syntax.all.*
import akka.http.scaladsl.model.{HttpHeader, StatusCode}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

/**
  * Defines an original source (what has been provided by clients during the api call)
  *
  *   - We preserve all the provided values (i.e evene null values are preserved)
  */
sealed trait OriginalSource extends Product with Serializable {

  /**
    * The resource representation to annotate data and compute the conditional cache headers
    */
  def resourceF: ResourceF[Unit]

  /**
    * The original payload
    */
  def source: Json
}

object OriginalSource {

  /**
    * Standard original source
    *   - Only the payload provided by the user will be returned
    */
  final private case class Standard(resourceF: ResourceF[Unit], source: Json) extends OriginalSource

  /**
    * Annotated original source
    *   - Injects alongside the original source, the metadata context (ex: audit values, @id, ...)
    */
  final private case class Annotated(resourceF: ResourceF[Unit], source: Json)(implicit val baseUri: BaseUri)
      extends OriginalSource

  def apply[A](resourceF: ResourceF[A], source: Json, annotated: Boolean)(implicit baseUri: BaseUri): OriginalSource = {
    if (annotated)
      Annotated(resourceF.void, source)
    else
      apply(resourceF, source)
  }

  def apply[A](resourceF: ResourceF[A], source: Json): OriginalSource = Standard(resourceF.void, source)

  def annotated[A](resourceF: ResourceF[A], source: Json)(implicit baseUri: BaseUri): OriginalSource =
    apply(resourceF, source, annotated = true)

  implicit val originalSourceEncoder: Encoder[OriginalSource] =
    Encoder.instance {
      case standard: Standard =>
        standard.source
      case value: Annotated   =>
        implicit val baseUri: BaseUri = value.baseUri
        val sourceWithoutMetadata     = value.source.removeMetadataKeys()
        val metadataJson              = value.resourceF.asJson
        metadataJson.deepMerge(sourceWithoutMetadata).addContext(contexts.metadata)
    }

  implicit val originalSourceHttpResponseFields: HttpResponseFields[OriginalSource] = {
    val resourceFHttpResponseField = ResourceF.resourceFHttpResponseFields[Unit]
    new HttpResponseFields[OriginalSource] {
      override def statusFrom(value: OriginalSource): StatusCode       =
        resourceFHttpResponseField.statusFrom(value.resourceF)
      override def headersFrom(value: OriginalSource): Seq[HttpHeader] =
        resourceFHttpResponseField.headersFrom(value.resourceF)
      override def entityTag(value: OriginalSource): Option[String]    =
        resourceFHttpResponseField.entityTag(value.resourceF)
    }
  }

}
