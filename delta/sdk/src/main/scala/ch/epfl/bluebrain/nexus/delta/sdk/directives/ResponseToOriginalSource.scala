package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.server.Directives.{complete, onSuccess, reject}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{conditionalCache, requestEncoding}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax.EncoderOps

/**
  * Handles serialization of [[OriginalSource]] and generates the appropriate response headers
  */
trait ResponseToOriginalSource {
  def apply(): Route
}

object ResponseToOriginalSource extends RdfMarshalling {

  // To serialize errors to compacted json-ld
  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  implicit private def originalSourceMarshaller(implicit
      ordering: JsonKeyOrdering
  ): ToEntityMarshaller[OriginalSource] =
    jsonMarshaller(ordering, sourcePrinter).compose(_.asJson)

  private[directives] def apply[E: JsonLdEncoder](
      io: IO[Either[Response[E], Complete[OriginalSource]]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToOriginalSource =
    () => {
      val ioRoute = io.flatMap {
        case Left(r: Reject[E])                 => IO.pure(reject(r))
        case Left(e: Complete[E])               => e.value.toCompactedJsonLd.map(r => complete(e.status, e.headers, r.json))
        case Right(v: Complete[OriginalSource]) =>
          IO.pure {
            requestEncoding { encoding =>
              conditionalCache(v.entityTag, v.lastModified, MediaTypes.`application/json`, encoding) {
                complete(v.status, v.headers, v.value)
              }
            }
          }
      }
      onSuccess(ioRoute.unsafeToFuture())(identity)
    }

  implicit def ioOriginalPayloadComplete[E: JsonLdEncoder: HttpResponseFields](
      io: IO[Either[E, OriginalSource]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToOriginalSource = {
    val ioComplete = io.map {
      _.bimap(e => Complete(e), originalSource => Complete(originalSource))
    }
    ResponseToOriginalSource(ioComplete)
  }

  implicit def ioResponseOriginalPayloadComplete[E: JsonLdEncoder](
      io: IO[Either[Response[E], OriginalSource]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToOriginalSource = {
    val ioComplete = io.map {
      _.map(originalSource => Complete(originalSource))
    }
    ResponseToOriginalSource(ioComplete)
  }
}
