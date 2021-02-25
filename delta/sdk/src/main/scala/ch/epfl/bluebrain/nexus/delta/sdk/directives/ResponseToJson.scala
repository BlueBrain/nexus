package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJson.UseRight
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

object ResponseToJson extends JsonValueInstances {

  private[directives] type UseRight = Either[Response[Unit], Complete[Json]]

  private[directives] def apply[E: JsonLdEncoder](
      uio: UIO[Either[Response[E], Complete[Json]]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering
  ): ResponseToJsonType = ResponseToJsonType(`application/json`, uio)
}

sealed trait JsonValueInstances {

  implicit def uioJson(
      uio: UIO[Json]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering
  ): ResponseToJsonType =
    ResponseToJson(uio.map[UseRight](v => Right(Complete(OK, Seq.empty, v))))

  implicit def ioJson[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Json]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering
  ): ResponseToJsonType =
    ResponseToJson(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

}
