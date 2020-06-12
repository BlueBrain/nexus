package ch.epfl.bluebrain.nexus.service.marshallers

import akka.http.scaladsl.marshalling.GenericMarshallers.eitherMarshaller
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.iam.acls.AclRejection
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.orderedKeys
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError.InternalError
import ch.epfl.bluebrain.nexus.service.routes.ResourceRejection
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe._
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object instances extends FailFastCirceSupport {

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    Encoder.encodeString.contramap(fd => s"${fd.toMillis} ms")

  implicit val resourceRejectionEncoder: Encoder[ResourceRejection] =
    Encoder.instance {
      case r: ProjectRejection      => Encoder[ProjectRejection].apply(r)
      case r: OrganizationRejection => Encoder[OrganizationRejection].apply(r)
      case r: AclRejection          => Encoder[AclRejection].apply(r)
      case r: RealmRejection        => Encoder[RealmRejection].apply(r)
      case r: PermissionsRejection  => Encoder[PermissionsRejection].apply(r)
      case _                        => Encoder[ServiceError].apply(InternalError("unspecified"))
    }

  implicit val resourceRejectionStatusFrom: StatusFrom[ResourceRejection] =
    StatusFrom {
      case r: OrganizationRejection => OrganizationRejection.organizationStatusFrom(r)
      case r: ProjectRejection      => ProjectRejection.projectStatusFrom(r)
      case r: AclRejection          => AclRejection.aclRejectionStatusFrom(r)
      case r: RealmRejection        => RealmRejection.realmRejectionStatusFrom(r)
      case r: PermissionsRejection  => PermissionsRejection.permissionsRejectionStatusFrom(r)
    }

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`)

  /**
    * `Json` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  implicit final def jsonLd(implicit
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      keys: OrderedKeys = orderedKeys
  ): ToEntityMarshaller[Json] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map(contentType =>
      Marshaller.withFixedContentType[Json, MessageEntity](contentType) { json =>
        HttpEntity(`application/ld+json`, printer.print(json.sortKeys))
      }
    )
    Marshaller.oneOf(marshallers: _*)
  }

  /**
    * `A` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def httpEntity[A](implicit
      encoder: Encoder[A],
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      keys: OrderedKeys = orderedKeys
  ): ToEntityMarshaller[A] =
    jsonLd.compose(encoder.apply)

  /**
    * `Either[Rejection,A]` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def either[A: Encoder, B <: ResourceRejection: StatusFrom: Encoder](implicit
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  ): ToResponseMarshaller[Either[B, A]] =
    eitherMarshaller(rejection[B], httpEntity[A])

  /**
    * `Rejection` => HTTP response
    *
    * @return marshaller for Rejection value
    */
  implicit final def rejection[A <: ResourceRejection: Encoder](implicit
      statusFrom: StatusFrom[A],
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      ordered: OrderedKeys = orderedKeys
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
        case None    => Task.raiseError(AdminError.NotFound)
      }.runToFuture
  }
}
