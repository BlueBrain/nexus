package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/html`}
import akka.http.scaladsl.model.StatusCodes.{Redirection, SeeOther}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, `Last-Event-ID`, Accept, RawHeader}
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
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
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Encoder
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

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
  def emit(response: ResponseToSse): Route = response()

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

  def requestMediaType: Directive1[MediaType] =
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
      fusionResourceUri(projectRef, id),
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

  /**
    * If the `Accept` header is set to `text/html`, redirect to the provided uri if the feature is enabled
    */
  def emitOrFusionRedirect(fusionUri: UIO[Uri], emitDelta: Route)(implicit config: FusionConfig, s: Scheduler): Route =
    extractRequest { req =>
      if (config.enableRedirects && req.header[Accept].exists(_.mediaRanges.contains(fusionRange))) {
        emitRedirect(SeeOther, ResponseToRedirect.uioRedirect(fusionUri))
      } else
        emitDelta
    }

  /**
    * Extracts an [[Offset]] value from the ''Last-Event-ID'' header, defaulting to [[Offset.Start]]. An invalid value
    * will result in an [[MalformedHeaderRejection]].
    */
  def lastEventId: Directive1[Offset] =
    optionalHeaderValueByName(`Last-Event-ID`.name).map(_.map(id => `Last-Event-ID`(id))).flatMap {
      case Some(value) =>
        value.id.toLongOption match {
          case None    =>
            val msg =
              s"Invalid '${`Last-Event-ID`.name}' header value '${value.id}', expected a Long value."
            reject(MalformedHeaderRejection(`Last-Event-ID`.name, msg))
          case Some(o) => provide(Offset.at(o))
        }
      case None        => provide(Offset.Start)
    }

  /** The URI of a resource in fusion (given a project & id pair) */
  def fusionResourceUri(projectRef: ProjectRef, id: IdSegmentRef)(implicit config: FusionConfig): UIO[Uri] =
    UIO.pure {
      val resourceBase =
        config.base / projectRef.organization.value / projectRef.project.value / "resources" / id.value.asString
      id match {
        case _: Latest        => resourceBase
        case Revision(_, rev) => resourceBase.withQuery(Uri.Query("rev" -> rev.toString))
        case Tag(_, tag)      => resourceBase.withQuery(Uri.Query("tag" -> tag.value))
      }
    }

  /** The URI of fusion's main login page */
  def fusionLoginUri(implicit config: FusionConfig): UIO[Uri] =
    UIO.pure { config.base / "login" }

  /** The URI of fusion's id resolution endpoint */
  def fusionResolveUri(id: Uri)(implicit config: FusionConfig): UIO[Uri] =
    UIO.pure { config.base / "resolve" / id.toString }

  /** Injects a `Vary: Accept,Accept-Encoding` into the response */
  def varyAcceptHeaders: Directive0 =
    vary(Set(Accept.name, `Accept-Encoding`.name))

  private def vary(headers: Set[String]): Directive0 =
    respondWithHeader(RawHeader("Vary", headers.mkString(",")))

  private def respondWithHeader(responseHeader: HttpHeader): Directive0 =
    mapSuccessResponse(r => r.withHeaders(r.headers :+ responseHeader))

  private def mapSuccessResponse(f: HttpResponse => HttpResponse): Directive0 =
    mapRouteResultPF {
      case RouteResult.Complete(response) if response.status.isSuccess => RouteResult.Complete(f(response))
    }
}
