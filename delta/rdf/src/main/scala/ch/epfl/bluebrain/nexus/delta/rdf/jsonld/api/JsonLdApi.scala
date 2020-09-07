package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, JsonLdContext, RemoteContextResolution}
import io.circe.Json
import monix.bio.IO
import org.apache.jena.rdf.model.Model

/**
  * Json-LD high level API as defined in https://www.w3.org/TR/json-ld11-api/
  */
trait JsonLdApi {
  def compact[Ctx <: JsonLdContext](input: Json, ctx: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, (Json, Ctx)]

  def expand(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Json]

  def frame[Ctx <: JsonLdContext](input: Json, frame: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, (Json, Ctx)]

  def toRdf(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Model]

  def fromRdf(input: Model)(implicit
      opts: JsonLdOptions
  ): IO[RdfError, Json]

  def context[Ctx <: JsonLdContext](value: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Ctx]

}

object JsonLdApi {
  implicit val jsonLdJavaAPI: JsonLdApi = JsonLdJavaApi
}
