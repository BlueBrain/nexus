package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{MediaType, StatusCode}
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

trait DeltaDirectives {

  private val mediaTypes =
    Seq(`application/ld+json`, `application/n-triples`, `application/vnd.graphviz`)

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
      io: UIO[A],
      successStatus: => StatusCode
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, (StatusCode, JsonLd)]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted => io.flatMap(_.toCompactedJsonLd).map(v => successStatus -> v)
      case JsonLdFormat.Expanded  => io.flatMap(_.toExpandedJsonLd).map(v => successStatus -> v)
    }

  /**
    * Converts the passed value wrapped in an [[IO]] to the appropriate [[JsonLdFormat]] depending on the ''format'' query param.
    * The error channel and the regular channel are converted to [[JsonLd]] with their respective status codes
    *
    * @param io            the value to convert to JSON-LD wrapped on an [[IO]]
    * @param successStatus the status code to return when the IO is successful
    * @return a [[JsonLd]] with its status code wrapped on an [[IO]]
    */
  def jsonldFormat[A: JsonLdEncoder, E: JsonLdEncoder](
      io: IO[E, A],
      successStatus: => StatusCode
  )(implicit cr: RemoteContextResolution, statusFrom: StatusFrom[E]): Directive1[IO[RdfError, (StatusCode, JsonLd)]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted =>
        io.attempt.flatMap {
          case Left(err)    => err.toCompactedJsonLd.map(v => statusFrom(err) -> v)
          case Right(value) => value.toCompactedJsonLd.map(v => successStatus -> v)
        }
      case JsonLdFormat.Expanded  =>
        io.attempt.flatMap {
          case Left(err)    => err.toExpandedJsonLd.map(v => statusFrom(err) -> v)
          case Right(value) => value.toExpandedJsonLd.map(v => successStatus -> v)
        }
    }

  /**
    * Extracts the first mediaType found in the ''Accept'' Http request header that matches the delta service ''mediaTypes''.
    * If the Accept header does not match any of the service supported ''mediaTypes'',
    * an [[UnacceptedResponseContentTypeRejection]] is returned
    */
  def requestMediaType: Directive1[MediaType] =
    extractRequest.flatMap { req =>
      val ct = new MediaTypeNegotiator(req.headers)
      ct.acceptedMediaRanges.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => mediaTypes.find(mt => mr.matches(mt))
      } match {
        case Some(value) => provide(value)
        case None        => reject(UnacceptedResponseContentTypeRejection(mediaTypes.map(mt => Alternative(mt)).toSet))
      }
    }
}

object DeltaDirectives extends DeltaDirectives

trait DeltaRouteDirectives extends DeltaDirectives with DeltaMarshalling {

  private val jsonMediaTypes =
    Seq(`application/ld+json`, `application/json`)

  /**
    * Completes a passed [[UIO]] of ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[UIO]]
    */
  def completeUIO[A: JsonLdEncoder](
      status: StatusCode,
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if jsonMediaTypes.contains(mediaType)   =>
        jsonldFormat(io, status).apply { formatted =>
          onSuccess(formatted.runToFuture) { (status, jsonLd) => complete(status, jsonLd) }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val f = io.flatMap(_.toNTriples).map(a => status -> a).runToFuture
        onSuccess(f) { (status, ntriples) => complete(status, ntriples) }

      case _                                                 => // `application/vnd.graphviz`
        val f = io.flatMap(_.toDot).map(a => status -> a).runToFuture
        onSuccess(f) { (status, dot) => complete(status, dot) }
    }

  /**
    * Completes a passed [[IO]] of ''E'' and ''A'' with the desired output format using the implicitly available [[JsonLdEncoder]].
    * Both error channel and normal channel are converted to the desired output format.
    *
    * @param status the returned HTTP status code
    * @param io     the value to be returned, wrapped in an [[IO]]
    */
  def completeIO[E: JsonLdEncoder, A: JsonLdEncoder](
      status: StatusCode,
      io: IO[E, A]
  )(implicit s: Scheduler, statusFrom: StatusFrom[E], cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if jsonMediaTypes.contains(mediaType)   =>
        jsonldFormat(io, status).apply { formatted =>
          onSuccess(formatted.runToFuture) { (status, jsonLd) => complete(status, jsonLd) }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val formatted = io.attempt.flatMap {
          case Left(value)  => value.toNTriples.map(a => statusFrom(value) -> a)
          case Right(value) => value.toNTriples.map(a => status -> a)
        }
        onSuccess(formatted.runToFuture) { (status, ntriples) => complete(status, ntriples) }

      case _                                                 => // `application/vnd.graphviz`
        val formatted = io.attempt.flatMap {
          case Left(value)  => value.toDot.map(a => statusFrom(value) -> a)
          case Right(value) => value.toDot.map(a => status -> a)
        }
        onSuccess(formatted.runToFuture) { (status, dot) => complete(status, dot) }
    }
}

object DeltaRouteDirectives extends DeltaRouteDirectives
