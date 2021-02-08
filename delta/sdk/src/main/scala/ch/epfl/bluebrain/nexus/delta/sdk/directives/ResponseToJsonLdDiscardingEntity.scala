package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJsonLd.UseRight
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import monix.bio.UIO
import monix.execution.Scheduler
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import io.circe.Encoder
import io.circe.syntax._

sealed trait ResponseToJsonLdDiscardingEntity {
  def apply(statusOverride: Option[StatusCode]): Route
}

object ResponseToJsonLdDiscardingEntity extends DiscardValueInstances {

  private[directives] def apply[A: JsonLdEncoder: Encoder](
      uio: UIO[Complete[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLdDiscardingEntity =
    new ResponseToJsonLdDiscardingEntity {

      private def fallbackAsPlainJson =
        onSuccess(uio.runToFuture) { case Complete(status, headers, value) =>
          complete(status, headers, value.asJson)
        }

      override def apply(statusOverride: Option[StatusCode]): Route =
        extractRequest { request =>
          extractMaterializer { implicit mat =>
            request.discardEntityBytes()
            ResponseToJsonLd(uio.map[UseRight[A]](Right(_))).apply(statusOverride) ~ fallbackAsPlainJson
          }
        }
    }
}

sealed trait DiscardValueInstances extends DiscardLowPriorityValueInstances {

  implicit def uioValue[A: JsonLdEncoder: Encoder](
      uio: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLdDiscardingEntity =
    ResponseToJsonLdDiscardingEntity(uio.map(Complete(OK, Seq.empty, _)))

  implicit def valueWithHttpResponseFields[A: JsonLdEncoder: HttpResponseFields: Encoder](
      value: A
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLdDiscardingEntity =
    ResponseToJsonLdDiscardingEntity(UIO.pure(Complete(value)))

}

sealed trait DiscardLowPriorityValueInstances {
  implicit def valueWithoutHttpResponseFields[A: JsonLdEncoder: Encoder](
      value: A
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLdDiscardingEntity =
    ResponseToJsonLdDiscardingEntity(UIO.pure(Complete(OK, Seq.empty, value)))

}
