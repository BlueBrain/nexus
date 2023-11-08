package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceGenerationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, SchemaResource}
import io.circe.Json

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

  def asJson(implicit base: BaseUri, rcr: RemoteContextResolution): IO[Json] = {
    for {
      schema          <- schema.fold(emptySchema)(toJsonField("schema", _))
      resourceOrError <- attempt.fold(
                           toJsonField("error", _),
                           toJsonField("result", _)
                         )
    } yield schema deepMerge resourceOrError
  }

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

  val emptySchema: IO[Json] = IO.pure(Json.obj())
}
