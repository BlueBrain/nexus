package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaType, StatusCode}
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, MediaTypeNegotiator, Route, UnacceptedResponseContentTypeRejection}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import io.circe.{Json, Printer}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import scala.collection.immutable.Seq

trait IOSupport {

  private val mediaTypes = Seq(
    `application/ld+json`,
    `application/n-triples`,
    `application/vnd.graphviz`
  )

  def jsonLdFormat: Directive1[JsonLdFormat] =
    parameter("format".?).map {
      case Some("compacted") => JsonLdFormat.Compacted
      case Some("expanded")  => JsonLdFormat.Expanded
      case _                 => JsonLdFormat.Compacted
    }

  implicit private val jsonLdApi: JsonLdApi      = JsonLdApi.jsonLdJavaAPI
  implicit private val jsonLdOpts: JsonLdOptions = JsonLdOptions.empty

  def completeUIO[A](status: StatusCode, io: UIO[A])(implicit
      s: Scheduler,
      A: JsonLdEncoder[A],
      cr: RemoteContextResolution
  ): Route = {
    extractRequest { req =>
      val ct              = new MediaTypeNegotiator(req.headers)
      val mediaTypeOption = ct.acceptedMediaRanges.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => mediaTypes.find(mt => mr.matches(mt))
      }
      mediaTypeOption match {
        case Some(mediaType) if mediaType == `application/ld+json` || mediaType == `application/json` =>
          jsonLdFormat {
            case JsonLdFormat.Compacted =>
              val f = io.flatMap(a => A.compact(a)).map(a => status -> a).runToFuture
              onSuccess(f) { case (s, j) => marshal(s, j) }
            case JsonLdFormat.Expanded  =>
              val f = io.flatMap(a => A.expand(a)).map(a => status -> a).runToFuture
              onSuccess(f) { case (s, j) => marshal(s, j) }
          }
        case Some(mediaType) if mediaType == `application/n-triples`                                  =>
          val f = io.flatMap(a => A.ntriples(a)).map(a => status -> a).runToFuture
          onSuccess(f) { case (s, j) => marshal(s, j) }
        case Some(mediaType) if mediaType == `application/vnd.graphviz`                               =>
          val f = io.flatMap(a => A.dot(a)).map(a => status -> a).runToFuture
          onSuccess(f) { case (s, j) => marshal(s, j) }
        case None                                                                                     =>
          reject(UnacceptedResponseContentTypeRejection(mediaTypes.map(mt => Alternative(mt)).toSet))
      }
    }
  }

  def completeIO[E, A](status: StatusCode, io: IO[E, A])(implicit
      s: Scheduler,
      A: JsonLdEncoder[A],
      E: JsonLdEncoder[E],
      ESF: StatusFrom[E],
      cr: RemoteContextResolution
  ): Route = {
    extractRequest { req =>
      val ct              = new MediaTypeNegotiator(req.headers)
      val mediaTypeOption = ct.acceptedMediaRanges.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => mediaTypes.find(mt => mr.matches(mt))
      }
      mediaTypeOption match {
        case Some(mediaType) if mediaType == `application/ld+json` || mediaType == `application/json` =>
          jsonLdFormat {
            case JsonLdFormat.Compacted =>
              val f = io.attempt.flatMap {
                case Left(err)    => E.compact(err).map(cj => ESF.statusOf(err) -> cj)
                case Right(value) => A.compact(value).map(a => status -> a)
              }.runToFuture
              onSuccess(f) { case (s, j) => marshal(s, j) }
            case JsonLdFormat.Expanded  =>
              val f = io.attempt.flatMap {
                case Left(err)    => E.expand(err).map(cj => ESF.statusOf(err) -> cj)
                case Right(value) => A.expand(value).map(a => status -> a)
              }.runToFuture
              onSuccess(f) { case (s, j) => marshal(s, j) }
          }
        case Some(mediaType) if mediaType == `application/n-triples`                                  =>
          val f = io.attempt.flatMap {
            case Left(err)    => E.ntriples(err).map(cj => ESF.statusOf(err) -> cj)
            case Right(value) => A.ntriples(value).map(a => status -> a)
          }.runToFuture
          onSuccess(f) { case (s, j) => marshal(s, j) }
        case Some(mediaType) if mediaType == `application/vnd.graphviz`                               =>
          val f = io.attempt.flatMap {
            case Left(err)    => E.dot(err).map(cj => ESF.statusOf(err) -> cj)
            case Right(value) => A.dot(value).map(a => status -> a)
          }.runToFuture
          onSuccess(f) { case (s, j) => marshal(s, j) }
        case None                                                                                     =>
          reject(UnacceptedResponseContentTypeRejection(mediaTypes.map(mt => Alternative(mt)).toSet))
      }
    }
  }

  def marshal(status: StatusCode, value: JsonLd): Route                                                   =
    complete(status -> value)

  def marshal(status: StatusCode, value: NTriples): Route                                                 =
    complete(status -> value)

  def marshal(status: StatusCode, value: Dot): Route                                                      =
    complete(status -> value)

  implicit def jsonLdMarshaller(implicit printer: Printer = Printer.noSpaces): ToEntityMarshaller[JsonLd] =
    Marshaller.withFixedContentType(ContentType(`application/ld+json`)) { jsonLd =>
      HttpEntity(
        `application/ld+json`,
        ByteString(printer.printToByteBuffer(jsonLd.json, `application/ld+json`.charset.nioCharset()))
      )
    }

  implicit def ntriplesMarshaller: ToEntityMarshaller[NTriples] =
    Marshaller.withFixedContentType(ContentType(`application/n-triples`)) { ntriples =>
      HttpEntity(`application/n-triples`, ntriples.value)
    }

  implicit def dotMarshaller: ToEntityMarshaller[Dot] =
    Marshaller.withFixedContentType(ContentType(`application/vnd.graphviz`)) { dot =>
      HttpEntity(`application/vnd.graphviz`, dot.value)
    }

}

object IOSupport extends IOSupport {

  /**
    * Data type which holds the ordering for the JSON-LD keys.
    *
   * @param keys list of strings which defines the ordering for the JSON-LD keys
    */
  final case class OrderedKeys(keys: List[String]) {
    lazy val withPosition: Map[String, Int] = keys.zipWithIndex.toMap
  }

  object OrderedKeys {

    /**
      * Construct an empty [[OrderedKeys]]
      */
    final val empty: OrderedKeys = new OrderedKeys(List(""))
  }

  val orderedKeys: OrderedKeys = OrderedKeys(
    List(
      "@context",
      "@id",
      "@type",
      "code",
      "message",
      "details",
//      nxv.reason.prefix,
//      nxv.description.name,
//      nxv.`@base`.name,
//      nxv.`@vocab`.name,
//      nxv.apiMappings.name,
//      nxv.prefix.name,
//      nxv.namespace.name,
//      nxv.total.prefix,
//      nxv.maxScore.prefix,
//      nxv.results.prefix,
//      nxv.score.prefix,
//      nxv.resourceId.prefix,
//      nxv.organization.prefix,
      "sourceId",
      "projectionId",
      "totalEvents",
      "processedEvents",
      "evaluatedEvents",
      "remainingEvents",
      "discardedEvents",
      "failedEvents",
      "sources",
      "projections",
      "rebuildStrategy",
//      nxv.project.prefix,
      "",
//      nxv.label.prefix,
//      nxv.organizationUuid.prefix,
//      nxv.organizationLabel.prefix,
      "_path",
//      nxv.grantTypes.prefix,
//      nxv.issuer.prefix,
//      nxv.keys.prefix,
//      nxv.authorizationEndpoint.prefix,
//      nxv.tokenEndpoint.prefix,
//      nxv.userInfoEndpoint.prefix,
//      nxv.revocationEndpoint.prefix,
//      nxv.endSessionEndpoint.prefix,
      "readPermission",
      "writePermission"
//      nxv.algorithm.prefix,
//      nxv.self.prefix,
//      nxv.constrainedBy.prefix,
//      nxv.project.prefix,
//      nxv.projectUuid.prefix,
//      nxv.organizationUuid.prefix,
//      nxv.rev.prefix,
//      nxv.deprecated.prefix,
//      nxv.createdAt.prefix,
//      nxv.createdBy.prefix,
//      nxv.updatedAt.prefix,
//      nxv.updatedBy.prefix,
//      nxv.incoming.prefix,
//      nxv.outgoing.prefix,
//      nxv.instant.prefix,
//      nxv.expiresInSeconds.prefix,
//      nxv.eventSubject.prefix
    )
  )

  implicit class RichJson(val json: Json) extends AnyVal {
    def sortKeys: Json = json
  }

}
