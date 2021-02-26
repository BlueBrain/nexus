package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{Response, ResponseToJsonType}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, RdfMarshalling}
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

trait ResponseToSparqlJson {

  def apply(): Route
}

object ResponseToSparqlJson {

  implicit def ioSparql[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, SparqlResults]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering
  ): ResponseToSparqlJson =
    apply(io.mapError(Complete(_)).map(v => Complete(OK, Seq.empty, v.asJson)).attempt)

  private[routes] def apply[E: JsonLdEncoder](
      uio: UIO[Either[Response[E], Complete[Json]]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToSparqlJson =
    new ResponseToSparqlJson {
      override def apply(): Route = {
        implicit val m = RdfMarshalling.customContentTypeJsonMarshaller(RdfMediaTypes.`application/sparql-results+json`)
        ResponseToJsonType(RdfMediaTypes.`application/sparql-results+json`, uio)
      }
    }
}
