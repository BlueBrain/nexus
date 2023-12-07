package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.effect.unsafe.implicits._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, JsonLdValue}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJsonLd.{RouteOutcome, UseLeft, UseRight}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, jsonLdFormatOrReject, mediaTypes, requestMediaType, unacceptedMediaTypeRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.JsonLdFormat.{Compacted, Expanded}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._

import java.nio.charset.StandardCharsets
import java.util.Base64

sealed trait ResponseToJsonLd {
  def apply(statusOverride: Option[StatusCode]): Route
}

object ResponseToJsonLd extends FileBytesInstances {

  private[directives] type UseLeft[A]  = Either[Response[A], Complete[Unit]]
  private[directives] type UseRight[A] = Either[Response[Unit], Complete[A]]

  sealed trait RouteOutcome[+E]
  object RouteOutcome {
    case class RouteRejected[E](value: Reject[E])           extends RouteOutcome[E]
    case class RouteFailed(value: Complete[JsonLdValue])    extends RouteOutcome[Nothing]
    case class RouteCompleted(value: Complete[JsonLdValue]) extends RouteOutcome[Nothing]
  }

  def apply[E](
      io: IO[RouteOutcome[E]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    new ResponseToJsonLd {

      // Some resources may not have been created in the system with a strict configuration
      // (and if they are, there is no need to check them again)
      implicit val api: JsonLdApi = JsonLdJavaApi.lenient

      override def apply(statusOverride: Option[StatusCode]): Route = {

        val ioFinal = statusOverride.fold(io) { newStatus =>
          io.map {
            case RouteOutcome.RouteCompleted(value) => RouteOutcome.RouteCompleted(value.copy(status = newStatus))
            case other                              => other
          }
        }

        def marshaller[R: ToEntityMarshaller](handle: JsonLdValue => IO[R]): Route = {
          val ioRoute = ioFinal.flatMap {
            case RouteOutcome.RouteRejected(rej)                               => IO.pure(reject(rej))
            case RouteOutcome.RouteFailed(Complete(status, headers, value))    =>
              handle(value).map(complete(status, headers, _))
            case RouteOutcome.RouteCompleted(Complete(status, headers, value)) =>
              handle(value).map(complete(status, headers, _))
          }
          onSuccess(ioRoute.unsafeToFuture())(identity)
        }

        requestMediaType {
          case mediaType if mediaType == `application/ld+json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(v => v.encoder.expand(v.value))
              case Compacted => marshaller(v => v.encoder.compact(v.value))
            }

          case mediaType if mediaType == `application/json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(v => v.encoder.expand(v.value).map(_.json))
              case Compacted => marshaller(v => v.encoder.compact(v.value).map(_.json))
            }

          case mediaType if mediaType == `application/n-triples` => marshaller(v => v.encoder.ntriples(v.value))

          case mediaType if mediaType == `application/n-quads` => marshaller(v => v.encoder.nquads(v.value))

          case mediaType if mediaType == `text/vnd.graphviz` => marshaller(v => v.encoder.dot(v.value))

          case _ => reject(unacceptedMediaTypeRejection(mediaTypes))
        }
      }
    }

  def apply[E: JsonLdEncoder, A: JsonLdEncoder](
      io: IO[Either[Response[E], Complete[A]]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    apply(io.map[RouteOutcome[E]] {
      case Right(c: Complete[A]) => RouteOutcome.RouteCompleted(c.map(JsonLdValue(_)))
      case Left(c: Complete[E])  => RouteOutcome.RouteFailed(c.map(JsonLdValue(_)))
      case Left(rej: Reject[E])  => RouteOutcome.RouteRejected(rej)
    })

  def fromFile[E: JsonLdEncoder](
      io: IO[Either[Response[E], FileResponse]]
  )(implicit jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    new ResponseToJsonLd {

      // From the RFC 2047: "=?" charset "?" encoding "?" encoded-text "?="
      private def attachmentString(filename: String): String = {
        val encodedFilename = Base64.getEncoder.encodeToString(filename.getBytes(StandardCharsets.UTF_8))
        s"=?UTF-8?B?$encodedFilename?="
      }
      private val logger = Logger[ResponseToJsonLd]
      override def apply(statusOverride: Option[StatusCode]): Route = {
        val flattened = io.flatMap {
          _.traverse { fr =>
            logger.info(s"DTBDTB in file response JSON LD encoding for ${fr.metadata.filename}") >>
              fr.content.map {
                _.map { s =>
                  fr.metadata -> s
                }
              }
          }
        }

        onSuccess(flattened.unsafeToFuture()) {
          thing: Either[Response[E], Either[Complete[JsonLdValue], (FileResponse.Metadata, AkkaSource)]] =>
            logger.info(s"DTBDTB result of encoding was $thing").unsafeRunSync()
            thing match {
              case Left(complete: Complete[E])       => emit(complete)
              case Left(reject: Reject[E])           => emit(reject)
              case Right(Left(c))                    => emit(c)
              case Right(Right((metadata, content))) =>
                headerValueByType(Accept) { accept =>
                  if (accept.mediaRanges.exists(_.matches(metadata.contentType.mediaType))) {
                    val encodedFilename = attachmentString(metadata.filename)
                    respondWithHeaders(
                      RawHeader("Content-Disposition", s"""attachment; filename="$encodedFilename"""")
                    ) {
                      complete(statusOverride.getOrElse(OK), HttpEntity(metadata.contentType, content))
                    }
                  } else
                    reject(unacceptedMediaTypeRejection(Seq(metadata.contentType.mediaType)))
                }
            }
        }
      }
    }
}

sealed trait FileBytesInstances extends ValueInstances {
  implicit def ioFileBytesWithReject[E: JsonLdEncoder](
      io: IO[Either[Response[E], FileResponse]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(io)

  implicit def ioFileBytes[E: JsonLdEncoder: HttpResponseFields](
      io: IO[Either[E, FileResponse]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(io.map(_.leftMap(Complete(_))))

  implicit def fileBytesValue(
      value: FileResponse
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(IO.pure(Right(value)))

}

sealed trait ValueInstances extends LowPriorityValueInstances {

  implicit def ioCompleteWithReject[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[Either[E, Complete[A]]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map(_.leftMap(Complete(_))))

  implicit def ioValueWithReject[E: JsonLdEncoder](
      io: IO[Reject[E]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map[UseLeft[E]](Left(_)))

  implicit def ioValue[A: JsonLdEncoder](
      io: IO[A]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map[UseRight[A]](v => Right(Complete(OK, Seq.empty, v))))

  implicit def ioEitherValueOrReject[E: JsonLdEncoder, A: JsonLdEncoder](
      io: IO[Either[Response[E], A]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd = {
    ResponseToJsonLd(io.map(_.map(Complete(OK, Seq.empty, _))))
  }

  implicit def ioValueOrError[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[Either[E, A]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map(_.leftMap(Complete(_)).map(Complete(OK, Seq.empty, _))))

  implicit def ioJsonLdValue[E: JsonLdEncoder: HttpResponseFields](
      io: IO[Either[E, JsonLdValue]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map[RouteOutcome[E]] {
      case Left(e)      => RouteOutcome.RouteFailed(Complete(e).map[JsonLdValue](JsonLdValue(_)))
      case Right(value) => RouteOutcome.RouteCompleted(Complete(OK, Seq.empty, value))
    })

  implicit def rejectValue[E: JsonLdEncoder](
      value: Reject[E]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(IO.pure[UseLeft[E]](Left(value)))

  implicit def completeValue[A: JsonLdEncoder](
      value: Complete[A]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(IO.pure[UseRight[A]](Right(value)))

  implicit def valueWithHttpResponseFields[A: JsonLdEncoder: HttpResponseFields](
      value: A
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(IO.pure[UseRight[A]](Right(Complete(value))))
}

sealed trait LowPriorityValueInstances {
  implicit def valueWithoutHttpResponseFields[A: JsonLdEncoder](
      value: A
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(IO.pure[UseRight[A]](Right(Complete(OK, Seq.empty, value))))
}
