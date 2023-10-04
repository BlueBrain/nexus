package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, SchemaResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import io.circe.Json
import monix.bio.{IO, UIO}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ResourceGenerationResult._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder

/**
  * Result of the generation of a resource
  * @param schema
  *   the schema if it has been generated
  * @param attempt
  *   the result of the generation attempt
  */
final case class ResourceGenerationResult(
    schema: Option[SchemaResource],
    attempt: Either[ResourceRejection, DataResource]
) {

  def asJson(implicit base: BaseUri, rcr: RemoteContextResolution): UIO[Json] = {
    for {
      schema          <- schema.fold(emptySchema)(toJsonField("schema", _))
      resourceOrError <- attempt.fold(
                           toJsonField("error", _),
                           toJsonField("result", _)
                         )
    } yield schema deepMerge resourceOrError
  }.hideErrors

  private def toJsonField[A](fieldName: String, value: A)(implicit
      encoder: JsonLdEncoder[A],
      rcr: RemoteContextResolution
  ) =
    value.toCompactedJsonLd.map { v => v.json }.map { s => Json.obj(fieldName -> s) }

  private def toJsonField[A](fieldName: String, value: ResourceF[A])(implicit
      encoder: JsonLdEncoder[A],
      base: BaseUri,
      rcr: RemoteContextResolution
  ) =
    value.toCompactedJsonLd.map { v => v.json }.map { s => Json.obj(fieldName -> s) }
}

object ResourceGenerationResult {
  implicit private[model] val api: JsonLdApi = JsonLdJavaApi.lenient

  val emptySchema: IO[RdfError, Json] = IO.pure(Json.obj())
}
