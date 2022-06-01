package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/html`}
import akka.http.scaladsl.model.StatusCodes.{Redirection, SeeOther}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Encoder
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.util.Try

object DeltaDirectives extends DeltaDirectives

trait DeltaDirectives extends UriDirectives {

  // order is important
  val mediaTypes: List[MediaType.WithFixedCharset] =
    List(
      `application/ld+json`,
      `application/json`,
      `application/n-triples`,
      `application/n-quads`,
      `text/vnd.graphviz`
    )

  val fusionRange: MediaRange.One = MediaRange.One(`text/html`, 1f)

  /**
    * Completes the current Route with the provided conversion to any available entity marshaller
    */
  def emit(response: ResponseToMarshaller): Route =
    response(None)

  /**
    * Completes the current Route with the provided conversion to any available entity marshaller
    */
  def emit(status: StatusCode, response: ResponseToMarshaller): Route =
    response(Some(status))

  /**
    * Completes the current Route with the provided conversion to SSEs
    */
  def emit(response: ResponseToSse): Route =
    response()

  /**
    * Completes the current Route with the provided conversion to Json-LD
    */
  def emit(response: ResponseToJsonLd): Route =
    response(None)

  /**
    * Completes the current Route with the provided status code and conversion to Json-LD
    */
  def emit(status: StatusCode, response: ResponseToJsonLd): Route =
    response(Some(status))

  /**
    * Completes the current Route with the provided redirection and conversion to Json-LD in case of an error.
    */
  def emitRedirect(redirection: Redirection, response: ResponseToRedirect): Route =
    response(redirection)

  /**
    * Completes the current Route discarding the entity and completing with the provided conversion to Json-LD. If the
    * Json-LD cannot be be completed for any reason, it returns the plain Json representation
    */
  def discardEntityAndForceEmit(response: ResponseToJsonLdDiscardingEntity): Route =
    response(None)

  /**
    * Completes the current Route discarding the entity and completing with the provided status code and conversion to
    * Json-LD. If the Json-LD cannot be be completed for any reason, it returns the plain Json representation
    */
  def discardEntityAndForceEmit(status: StatusCode, response: ResponseToJsonLdDiscardingEntity): Route =
    response(Some(status))

  /**
    * Extracts an [[Offset]] value from the ''Last-Event-ID'' header, defaulting to [[NoOffset]]. An invalid value will
    * result in an [[MalformedHeaderRejection]].
    */
  def lastEventId: Directive1[Offset] = {
    optionalHeaderValueByName(`Last-Event-ID`.name).map(_.map(id => `Last-Event-ID`(id))).flatMap {
      case Some(value) =>
        val timeBasedUUID = Try(TimeBasedUUID(UUID.fromString(value.id))).toOption
        val sequence      = value.id.toLongOption.map(Sequence)
        timeBasedUUID orElse sequence match {
          case Some(value) => provide(value)
          case None        =>
            val msg =
              s"Invalid '${`Last-Event-ID`.name}' header value '${value.id}', expected either a Long value or a TimeBasedUUID."
            reject(MalformedHeaderRejection(`Last-Event-ID`.name, msg))
        }
      case None        => provide(NoOffset)
    }
  }

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] whenever the passed ''filter''
    * is true. If the [[PartialFunction]] does not apply, the error channel is left untouched.
    */
  def rejectOn[E: JsonLdEncoder: HttpResponseFields: Encoder, A](
      io: IO[E, A]
  )(filter: PartialFunction[E, Boolean]): IO[Response[E], A] =
    io.mapError {
      case err @ filter(true) => Reject(err)
      case err                => Complete(err)
    }

  def unacceptedMediaTypeRejection(values: Seq[MediaType]): UnacceptedResponseContentTypeRejection =
    UnacceptedResponseContentTypeRejection(values.map(mt => Alternative(mt)).toSet)

  private[directives] def requestMediaType: Directive1[MediaType] =
    extractRequest.flatMap { req =>
      HeadersUtils.findFirst(req.headers, mediaTypes) match {
        case Some(value) => provide(value)
        case None        => reject(unacceptedMediaTypeRejection(mediaTypes))
      }
    }

  /**
    * If the `Accept` header is set to `text/html`, redirect to the matching resource page in fusion if the feature is
    * enabled
    */
  def emitOrFusionRedirect(projectRef: ProjectRef, id: IdSegmentRef, emitDelta: Route)(implicit
      config: FusionConfig,
      s: Scheduler
  ): Route =
    emitOrFusionRedirect(
      UIO.pure {
        val resourceBase =
          config.base / projectRef.organization.value / projectRef.project.value / "resources" / id.value.asString
        id match {
          case _: Latest        => resourceBase
          case Revision(_, rev) => resourceBase.withQuery(Uri.Query("rev" -> rev.toString))
          case Tag(_, tag)      => resourceBase.withQuery(Uri.Query("tag" -> tag.value))
        }
      },
      emitDelta
    )

  /**
    * If the `Accept` header is set to `text/html`, redirect to the matching project page in fusion if the feature is
    * enabled
    */
  def emitOrFusionRedirect(projectRef: ProjectRef, emitDelta: Route)(implicit
      config: FusionConfig,
      s: Scheduler
  ): Route =
    emitOrFusionRedirect(
      UIO.pure(config.base / "admin" / projectRef.organization.value / projectRef.project.value),
      emitDelta
    )

  private def emitOrFusionRedirect(fusionUri: UIO[Uri], emitDelta: Route)(implicit config: FusionConfig, s: Scheduler) =
    extractRequest { req =>
      if (config.enableRedirects && req.header[Accept].exists(_.mediaRanges.contains(fusionRange))) {
        emitRedirect(SeeOther, fusionUri)
      } else
        emitDelta
    }
}
