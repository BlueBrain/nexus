package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.MediaTypes.`text/plain`
import akka.http.scaladsl.server.Directives.{extractRequest, provide}
import akka.http.scaladsl.server.{Directive, Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.kernel.RdfMediaTypes.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryResponse, SparqlQueryResponseType}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import org.http4s.MediaType as Http4sMediaType

trait BlazegraphViewsDirectives {

  private val queryMediaTypes: Seq[MediaType.NonBinary] =
    List(
      `application/sparql-results+json`,
      `application/sparql-results+xml`,
      `application/ld+json`,
      `application/n-triples`,
      `text/plain`,
      `application/rdf+xml`
    )

  /**
    * Completes with ''UnacceptedResponseContentTypeRejection'' immediately (without rejecting)
    */
  private def emitUnacceptedMediaType(implicit
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    discardEntityAndForceEmit(unacceptedMediaTypeRejection(queryMediaTypes))

  def queryResponseType(implicit
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Directive1[SparqlQueryResponseType.Aux[SparqlQueryResponse]] =
    extractRequest.flatMap { req =>
      HeadersUtils.findFirst(req.headers, queryMediaTypes).flatMap { akkaMediaType =>
        val http4sMediaType = new Http4sMediaType(
          akkaMediaType.mainType,
          akkaMediaType.subType,
          akkaMediaType.isCompressible,
          akkaMediaType.isCompressible,
          fileExtensions = akkaMediaType.fileExtensions
        )
        SparqlQueryResponseType.fromMediaType(http4sMediaType)
      } match {
        case Some(responseType) => provide(responseType.asInstanceOf[SparqlQueryResponseType.Generic])
        case None               => Directive(_ => emitUnacceptedMediaType)
      }
    }
}
