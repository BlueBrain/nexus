package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{ContentType, HttpCharsets, MediaType}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{requestMediaType, unacceptedMediaTypeRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJson.UseRight
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

sealed trait ResponseToJson {
  def apply(): Route
}

object ResponseToJson extends JsonValueInstances {

  private[directives] type UseLeft[A] = Either[Response[A], Complete[Json]]
  private[directives] type UseRight   = Either[Response[Unit], Complete[Json]]

  private[directives] def apply[E: JsonLdEncoder](
      uio: UIO[Either[Response[E], Complete[Json]]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering,
      responseMediaTypes: Seq[MediaType]
  ): ResponseToJson =
    new ResponseToJson {

      override def apply(): Route =
        requestMediaType(responseMediaTypes) {
          case mediaType if responseMediaTypes.contains(mediaType) =>
            val ioRoute = uio.flatMap {
              case Left(r: Reject[E])       => UIO.pure(reject(r))
              case Left(e: Complete[E])     => e.value.toCompactedJsonLd.map(r => complete(e.status, e.headers, r.json))
              case Right(v: Complete[Json]) =>
                implicit val contentType = ContentType(mediaType, () => HttpCharsets.`UTF-8`)
                UIO.pure(complete(v.status, v.headers, v.value))
            }
            onSuccess(ioRoute.runToFuture)(identity)

          case _ => reject(unacceptedMediaTypeRejection(responseMediaTypes))
        }
    }
}

sealed trait JsonValueInstances {

  implicit def uioJson(
      uio: UIO[Json]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering,
      responseMediaTypes: Seq[MediaType] = Seq(`application/json`)
  ): ResponseToJson =
    ResponseToJson(uio.map[UseRight](v => Right(Complete(OK, Seq.empty, v))))

  implicit def ioJson[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Json]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering,
      responseMediaTypes: Seq[MediaType] = Seq(`application/json`)
  ): ResponseToJson =
    ResponseToJson(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

}
