package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import io.circe.Json

object AnnotatedSource {

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  /**
    * Merges the source with the metadata of [[ResourceF]]
    */
  def apply(resourceF: ResourceF[_], source: Json)(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution
  ): IO[Json] =
    metadataJson(resourceF)
      .map(mergeOriginalPayloadWithMetadata(source, _))

  private def metadataJson(resource: ResourceF[_])(implicit baseUri: BaseUri, cr: RemoteContextResolution) = {
    implicit val resourceFJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] = ResourceF.defaultResourceFAJsonLdEncoder
    resourceFJsonLdEncoder
      .compact(resource.void)
      .map(_.json)
  }

  private def mergeOriginalPayloadWithMetadata(payload: Json, metadata: Json): Json = {
    getId(payload)
      .foldLeft(payload.deepMerge(metadata))(setId)
  }

  private def getId(payload: Json): Option[String] = payload.hcursor.get[String]("@id").toOption

  private def setId(payload: Json, id: String): Json =
    payload.hcursor.downField("@id").set(Json.fromString(id)).top.getOrElse(payload)

}
