package ch.epfl.bluebrain.nexus.marshallers

import akka.http.scaladsl.marshalling.GenericMarshallers.eitherMarshaller
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import ch.epfl.bluebrain.nexus.acls.AclRejection
import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport
import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.config.AppConfig
import ch.epfl.bluebrain.nexus.directives.StatusFrom
import ch.epfl.bluebrain.nexus.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.realms.RealmRejection
import ch.epfl.bluebrain.nexus.syntax.all._
import ch.epfl.bluebrain.nexus.{ResourceRejection, ServiceError}
import io.circe._
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object instances extends JsonLdCirceSupport {

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    Encoder.encodeString.contramap(fd => s"${fd.toMillis} ms")

  implicit val resourceRejectionEncoder: Encoder[ResourceRejection] =
    Encoder.instance {
      case r: AclRejection         => Encoder[AclRejection].apply(r)
      case r: RealmRejection       => Encoder[RealmRejection].apply(r)
      case r: PermissionsRejection => Encoder[PermissionsRejection].apply(r)
      case _                       => Encoder[ServiceError].apply(ServiceError.InternalError("unspecified"))
    }

  implicit val resourceRejectionStatusFrom: StatusFrom[ResourceRejection] =
    StatusFrom {
      case r: AclRejection         => AclRejection.aclRejectionStatusFrom(r)
      case r: RealmRejection       => RealmRejection.realmRejectionStatusFrom(r)
      case r: PermissionsRejection => PermissionsRejection.permissionsRejectionStatusFrom(r)
    }

  /**
    * `Either[Rejection,A]` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def either[A: Encoder, B <: ResourceRejection: StatusFrom: Encoder](
      implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  ): ToResponseMarshaller[Either[B, A]] =
    eitherMarshaller(rejection[B], marshallerHttp[A])

  /**
    * `Rejection` => HTTP response
    *
    * @return marshaller for Rejection value
    */
  implicit final def rejection[A <: ResourceRejection: Encoder](
      implicit statusFrom: StatusFrom[A],
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      ordered: OrderedKeys = AppConfig.orderedKeys
  ): ToResponseMarshaller[A] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map { contentType =>
      Marshaller.withFixedContentType[A, HttpResponse](contentType) { rejection =>
        HttpResponse(
          status = statusFrom(rejection),
          entity = HttpEntity(contentType, printer.print(rejection.asJson.sortKeys))
        )
      }
    }
    Marshaller.oneOf(marshallers: _*)
  }

  implicit class EitherTask[R <: ResourceRejection, A](task: Task[Either[R, A]])(implicit s: Scheduler) {
    def runWithStatus(code: StatusCode): Future[Either[R, (StatusCode, A)]] =
      task.map(_.map(code -> _)).runToFuture
  }

  implicit class OptionTask[A](task: Task[Option[A]])(implicit s: Scheduler) {
    def runNotFound: Future[A] =
      task.flatMap {
        case Some(a) => Task.pure(a)
        case None    => Task.raiseError(ServiceError.NotFound)
      }.runToFuture
  }
}
