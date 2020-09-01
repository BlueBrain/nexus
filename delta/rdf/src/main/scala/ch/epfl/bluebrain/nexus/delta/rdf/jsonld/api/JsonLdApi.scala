package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.IOErrorOr
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, JsonLdContext, RemoteContextResolution}
import io.circe.Json
import org.apache.jena.rdf.model.Model

/**
  * Json-LD high level API as defined in https://www.w3.org/TR/json-ld11-api/
  */
trait JsonLdApi {
  def compact[Ctx <: JsonLdContext](input: Json, ctx: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[(Json, Ctx)]

  def expand(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[Json]

  def frame[Ctx <: JsonLdContext](input: Json, frame: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[(Json, Ctx)]

  def toRdf(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[Model]

  def fromRdf(input: Model)(implicit
      opts: JsonLdOptions
  ): IOErrorOr[Json]

  def context[Ctx <: JsonLdContext](value: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[Ctx]

}

object JsonLdApi {
  implicit val jsonLdJavaAPI: JsonLdApi = JsonLdJavaApi
}
