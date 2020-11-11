package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.DeltaDirectives.Result
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.{HttpResponseFields, JsonLdFormat, QueryParamsUnmarshalling, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event, Label}
import ch.epfl.bluebrain.nexus.delta.syntax._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import scala.util.{Failure, Success, Try}

trait DeltaDirectives extends RdfMarshalling with QueryParamsUnmarshalling {

  // order is important
  private val mediaTypes =
    Seq(
      `application/ld+json`,
      `application/json`,
      `application/n-triples`,
      `text/vnd.graphviz`
    )

  /**
    * Extract the common searchParameters (deprecated, rev, createdBy, updatedBy) from query parameters
    */
  def searchParams(implicit
      base: BaseUri
  ): Directive[(Option[Boolean], Option[Long], Option[Subject], Option[Subject])] =
    parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("createdBy".as[Subject].?) &
      parameter("updatedBy".as[Subject].?)

  /**
    * When ''prefix'' exists, consumes the leading slash and the following ''prefix'' value.
    */
  def baseUriPrefix(prefix: Option[Label]): Directive[Unit] =
    prefix match {
      case Some(Label(prefixSegment)) => pathPrefix(prefixSegment)
      case None                       => tprovide(())
    }

  /**
    * Parse a segment in a label
    */
  def label: Directive1[Label] =
    pathPrefix(Segment).flatMap { str =>
      Label(str) match {
        case Left(err)    => reject(validationRejection(err.getMessage))
        case Right(label) => provide(label)
      }
    }

  /**
    * Parse two consecutive segment labels into a project reference
    */
  def projectRef: Directive1[ProjectRef] =
    (label & label).tmap { case (org, proj) =>
      ProjectRef(org, proj)
    }

  /**
    * Parse a segment in a UUID
    */
  def uuid: Directive1[UUID] =
    pathPrefix(Segment).flatMap { str =>
      Try(UUID.fromString(str)) match {
        case Failure(_)    => reject(validationRejection(s"Path segment '$str' is not a UUIDv4"))
        case Success(uuid) => provide(uuid)
      }
    }

  /**
    * Extracts pagination specific query params from the request or defaults to the preconfigured values.
    *
    * @param qs the preconfigured query settings
    */
  def paginated(implicit qs: PaginationConfig): Directive1[FromPagination] =
    (parameter(from.as[Int] ? 0) & parameter(size.as[Int] ? qs.defaultSize)).tmap { case (from, size) =>
      FromPagination(from.max(0).min(qs.fromLimit), size.max(1).min(qs.sizeLimit))
    }

  /**
    * Extracts the [[JsonLdFormat]] from the ''format'' query parameter
    */
  private def jsonLdFormat: Directive1[JsonLdFormat] =
    parameter("format".?).flatMap {
      case Some("compacted") => provide(JsonLdFormat.Compacted)
      case Some("expanded")  => provide(JsonLdFormat.Expanded)
      case Some(other)       => reject(InvalidRequiredValueForQueryParamRejection("format", "compacted|expanded", other))
      case None              => provide(JsonLdFormat.Compacted)
    }

  /**
    * Converts the passed value wrapped in an [[UIO]] to the appropriate [[JsonLdFormat]] depending on the ''format'' query param.
    *
    * @param io            the value to convert to JSON-LD wrapped on an [[IO]]
    * @param successStatus the status code to return
    * @return a [[JsonLd]] with its status code wrapped on an [[IO]]
    */
  def jsonldFormat[A: JsonLdEncoder](
      io: UIO[Option[A]],
      successStatus: => StatusCode,
      successHeaders: => Seq[HttpHeader]
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, Result[JsonLd]]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted => io.flatMap { v => Result.compactedJsonLd(v, successStatus, successHeaders) }
      case JsonLdFormat.Expanded  => io.flatMap { v => Result.expandedJsonLd(v, successStatus, successHeaders) }
    }

  /**
    * Converts the passed value wrapped in an [[IO]] to the appropriate [[JsonLdFormat]] depending on the ''format'' query param.
    * The error channel and the regular channel are converted to [[JsonLd]] with their respective status codes
    *
    * @param io             the value to convert to JSON-LD wrapped on an [[IO]]
    * @param successStatus  the status code to return when the IO is successful
    * @param successHeaders the Http Headers to return when the IO is successful
    * @return a [[JsonLd]] with its status code wrapped on an [[IO]]
    */
  def jsonldFormat[A: JsonLdEncoder, E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Option[A]],
      successStatus: => StatusCode,
      successHeaders: => Seq[HttpHeader]
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, Result[JsonLd]]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted =>
        io.attempt.flatMap {
          case Left(err)    =>
            err.toCompactedJsonLd.map(v => Result(err.status, err.headers, v: JsonLd))
          case Right(value) => Result.compactedJsonLd(value, successStatus, successHeaders)
        }
      case JsonLdFormat.Expanded  =>
        io.attempt.flatMap {
          case Left(err)    => err.toExpandedJsonLd.map(v => Result(err.status, err.headers, v: JsonLd))
          case Right(value) => Result.expandedJsonLd(value, successStatus, successHeaders)
        }
    }

  /**
    * Extracts the first mediaType found in the ''Accept'' Http request header that matches the delta service ''mediaTypes''.
    * If the Accept header does not match any of the service supported ''mediaTypes'',
    * an [[UnacceptedResponseContentTypeRejection]] is returned
    */
  def requestMediaType: Directive1[MediaType] =
    extractRequest.flatMap { req =>
      val ct       = new MediaTypeNegotiator(req.headers)
      val accepted = if (ct.acceptedMediaRanges.isEmpty) List(MediaRanges.`*/*`) else ct.acceptedMediaRanges
      accepted.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => mediaTypes.find(mt => mr.matches(mt))
      } match {
        case Some(value) => provide(value)
        case None        => reject(UnacceptedResponseContentTypeRejection(mediaTypes.map(mt => Alternative(mt)).toSet))
      }
    }

  /**
    * Extracts an [[Offset]] value from the ''Last-Event-ID'' header, defaulting to [[NoOffset]]. An invalid value will
    * result in an [[MalformedHeaderRejection]].
    */
  def lastEventId: Directive1[Offset] = {
    optionalHeaderValueByType(`Last-Event-ID`).flatMap {
      case Some(value) =>
        val timeBasedUUID = Try(TimeBasedUUID(UUID.fromString(value.id))).toOption
        val sequence      = value.id.toLongOption.map(Sequence)
        timeBasedUUID orElse sequence match {
          case Some(value) => provide(value)
          case None        =>
            reject(
              MalformedHeaderRejection(
                `Last-Event-ID`.name,
                s"Invalid '${`Last-Event-ID`.name}' header value '${value.id}', expected either a Long value or a TimeBasedUUID."
              )
            )
        }
      case None        => provide(NoOffset)
    }
  }

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    *
    * @param status  the returned HTTP status code
    * @param headers the returned HTTP Headers
    * @param io      the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIOOpt[A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if mediaType == `application/ld+json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd)
          }
        }

      case mediaType if mediaType == `application/json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd.json)
          }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val f = io.flatMap { v => Result.nTriples(v, status, headers) }.runToFuture
        onSuccess(f) { case Result(status, headers, ntriples) => complete(status, headers, ntriples) }

      case mediaType if mediaType == `text/vnd.graphviz`     =>
        val f = io.flatMap { v => Result.dot(v, status, headers) }.runToFuture
        onSuccess(f) { case Result(status, headers, dot) => complete(status, headers, dot) }

      case _                                                 => // reject
        reject(UnacceptedResponseContentTypeRejection(mediaTypes.toSet.map((mt: MediaType) => Alternative(mt))))
    }

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIO[A: JsonLdEncoder](
      status: => StatusCode,
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    completeUIOOpt(status, io.map(Some(_)))

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIOOpt[A: JsonLdEncoder](status: => StatusCode, io: UIO[Option[A]])(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    completeUIOOpt(status, Seq.empty, io)

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param io  the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIOOpt[A: JsonLdEncoder](
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    completeUIOOpt(StatusCodes.OK, Seq.empty, io)

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    *
    * @param io  the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIO[A: JsonLdEncoder](
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    completeUIOOpt(io.map(Some(_)))

  /**
    * Completes a passed [[UIO]] of search results of ''A'' with the desired output format
    * @param io the search result to be returned, wrapped in an [[UIO]]
    * @param additionalContext a context related to ''A''
    */
  def completeSearch[A](
      io: UIO[SearchResults[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      S: SearchEncoder[A],
      additionalContext: ContextValue
  ): Route =
    completeUIO(io)

  /**
    * Completes a passed [[UIO]] of sequence of ''A'' as [[UnscoredSearchResults]]
    * @param io the search result to be returned, wrapped in an [[UIO]]
    * @param additionalContext a context related to ''A''
    */
  def completeUnscoredSearch[A](
      io: UIO[Seq[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      S: SearchEncoder[A],
      additionalContext: ContextValue
  ): Route =
    completeUIO(io.map[SearchResults[A]](res => SearchResults(res.length.toLong, res)))

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status the returned HTTP status code
    * @param headers the returned HTTP Headers
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def discardEntityAndCompleteUIOOpt[A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    extractRequest { request =>
      extractMaterializer { implicit mat =>
        request.discardEntityBytes()
        completeUIOOpt(status, headers, io)
      }
    }

  /**
    * Completes a passed value of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    */
  def discardEntityAndComplete[A: JsonLdEncoder: HttpResponseFields](
      value: A
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    discardEntityAndCompleteUIO(status = value.status, headers = value.headers, io = UIO.pure(value))

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    *
    * @param status the returned HTTP status code
    * @param headers the returned HTTP Headers
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def discardEntityAndCompleteUIO[A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    discardEntityAndCompleteUIOOpt(status, headers, io.map(Some(_)))

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def discardEntityAndCompleteUIOOpt[A: JsonLdEncoder](
      status: => StatusCode,
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    discardEntityAndCompleteUIOOpt(status, Seq.empty, io)

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def discardEntityAndCompleteUIO[A: JsonLdEncoder](
      status: => StatusCode,
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    discardEntityAndCompleteUIOOpt(status, io.map(Some(_)))

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Before returning the response, the request data bytes will be discarded.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param io  the value to be returned, wrapped in an [[UIO]]
    */
  def discardEntityAndCompleteUIOOpt[A: JsonLdEncoder](
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    discardEntityAndCompleteUIOOpt(StatusCodes.OK, Seq.empty, io)

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status  the returned HTTP status code
    * @param headers the returned HTTP Headers
    * @param io      the value to be returned, wrapped in an [[IO]]
    */
  def completeIOOpt[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: IO[E, Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if mediaType == `application/ld+json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd)
          }
        }

      case mediaType if mediaType == `application/json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd.json)
          }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val formatted = io.attempt.flatMap {
          case Left(err)    => err.toNTriples.map(v => Result(err.status, err.headers, v))
          case Right(value) => Result.nTriples(value, status, headers)
        }
        onSuccess(formatted.runToFuture) { case Result(status, headers, ntriples) =>
          complete(status, headers, ntriples)
        }

      case mediaType if mediaType == `text/vnd.graphviz` =>
        val formatted = io.attempt.flatMap {
          case Left(err)    => err.toDot.map(v => Result(err.status, err.headers, v))
          case Right(value) => Result.dot(value, status, headers)
        }
        onSuccess(formatted.runToFuture) { case Result(status, headers, dot) =>
          complete(status, headers, dot)
        }

      case _ => // reject
        reject(UnacceptedResponseContentTypeRejection(mediaTypes.toSet.map((mt: MediaType) => Alternative(mt))))
    }

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    *
    * @param status  the returned HTTP status code
    * @param headers the returned HTTP Headers
    * @param io      the value to be returned, wrapped in an [[IO]]
    */
  def completeIO[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: IO[E, A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    completeIOOpt(status, headers, io.map(Some(_)))

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[IO]]
    */
  def completeIOOpt[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      status: => StatusCode,
      io: IO[E, Option[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    completeIOOpt(status, Seq.empty, io)

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[IO]]
    */
  def completeIO[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](status: => StatusCode, io: IO[E, A])(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    completeIOOpt(status, io.map(Some(_)))

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    * If the normal channel doesn't hold any value, a not found output is produced
    *
    * @param io  the value to be returned, wrapped in an [[IO]]
    */
  def completeIOOpt[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[E, Option[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    completeIOOpt(StatusCodes.OK, Seq.empty, io)

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    *
    * @param io  the value to be returned, wrapped in an [[IO]]
    */
  def completeIO[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](io: IO[E, A])(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    completeIOOpt(io.map(Some(_)))

  /**
    * Completes a stream of envelopes of events using the implicitly available [[JsonLdEncoder]].
    *
    * @param stream the stream to complete
    */
  def completeStream[E <: Event: JsonLdEncoder](
      stream: fs2.Stream[Task, Envelope[E]]
  )(implicit s: Scheduler, ordering: JsonKeyOrdering, cr: RemoteContextResolution): Route = {
    def encode(envelope: Envelope[E]): IO[RdfError, ServerSentEvent] =
      envelope.event.toCompactedJsonLd.map { jsonLd =>
        val id: String = envelope.offset match {
          case TimeBasedUUID(value) => value.toString
          case Sequence(value)      => value.toString
          case NoOffset             => -1L.toString
        }
        ServerSentEvent(
          data = jsonLd.json.sort.noSpaces,
          eventType = Some(envelope.eventType),
          id = Some(id)
        )
      }

    complete(Source.fromGraph[ServerSentEvent, Any](stream.evalMap(encode).toSource))
  }
}

object DeltaDirectives {

  private val notFound: ServiceError = ServiceError.NotFound

  final private[routes] case class Result[C](statusCode: StatusCode, hearders: Seq[HttpHeader], content: C)

  private[routes] object Result {

    /**
      * Constructs a result encoding the value as a [[CompactedJsonLd]]
      */
    def compactedJsonLd[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[JsonLd]] =
      value.fold(notFound.toCompactedJsonLd.map(v => Result(StatusCodes.NotFound, Nil, v: JsonLd))) { r: A =>
        r.toCompactedJsonLd.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[ExpandedJsonLd]]
      */
    def expandedJsonLd[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[JsonLd]] =
      value.fold(notFound.toExpandedJsonLd.map(v => Result(StatusCodes.NotFound, Nil, v: JsonLd))) { r: A =>
        r.toExpandedJsonLd.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[NTriples]]
      */
    def nTriples[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[NTriples]] =
      value.fold(notFound.toNTriples.map(v => Result(StatusCodes.NotFound, Nil, v))) { r: A =>
        r.toNTriples.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[Dot]]
      */
    def dot[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(implicit
        cr: RemoteContextResolution
    ): IO[RdfError, Result[Dot]] =
      value.fold(notFound.toDot.map(v => Result(StatusCodes.NotFound, Nil, v))) { r: A =>
        r.toDot.map { v => Result(successStatus, successHeaders, v) }
      }

  }

}
