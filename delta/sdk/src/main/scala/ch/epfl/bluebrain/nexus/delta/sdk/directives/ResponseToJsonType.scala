package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.unacceptedMediaTypeRejection
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import io.circe.Json
import monix.bio.UIO
import monix.execution.Scheduler

object ResponseToJsonType {
  def apply[E: JsonLdEncoder](mediaType: MediaType, uio: UIO[Either[Response[E], Complete[Json]]])(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      m: ToEntityMarshaller[Json]
  ): Route = extractRequest { request =>
    if (HeadersUtils.matches(request.headers, mediaType)) {
      val ioRoute = uio.flatMap {
        case Left(r: Reject[E])       => UIO.pure(reject(r))
        case Left(e: Complete[E])     => e.value.toCompactedJsonLd.map(r => complete(e.status, e.headers, r.json))
        case Right(v: Complete[Json]) => UIO.pure(complete(v.status, v.headers, v.value))
      }
      onSuccess(ioRoute.runToFuture)(identity)
    } else reject(unacceptedMediaTypeRejection(Seq(mediaType)))
  }
}
