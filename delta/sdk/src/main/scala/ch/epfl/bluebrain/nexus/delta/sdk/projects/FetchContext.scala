package ch.epfl.bluebrain.nexus.delta.sdk.projects

import akka.http.scaladsl.model.{HttpHeader, StatusCode}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{model, Organizations}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.{Encoder, JsonObject}

import scala.reflect.ClassTag

/**
  * Define the rules to fetch project context for read and write operations
  */
abstract class FetchContext[R <: Throwable: ClassTag] { self =>

  /**
    * The default api mappings
    */
  def defaultApiMappings: ApiMappings

  /**
    * Fetch a context for a read operation
    * @param ref
    *   the project to fetch the context from
    */
  def onRead(ref: ProjectRef): IO[ProjectContext]

  /**
    * Fetch context for a create operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext]

  /**
    * Fetch context for a modify operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext]

  /**
    * Map the rejection to another one
    * @param f
    *   the function from [[R]] to [[R2]]
    */
  def mapRejection[R2 <: Throwable: ClassTag](f: R => R2): FetchContext[R2] =
    new FetchContext[R2] {
      override def defaultApiMappings: ApiMappings = self.defaultApiMappings

      override def onRead(ref: ProjectRef): IO[ProjectContext] = self.onRead(ref).adaptError { case r: R =>
        f(r)
      }

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        self.onCreate(ref).adaptError { case r: R =>
          f(r)
        }

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        self.onModify(ref).adaptError { case r: R =>
          f(r)
        }
    }

}

object FetchContext {

  /**
    * A rejection allowing to align the different possible rejection when fetching a context
    */
  sealed trait ContextRejection extends SDKError {

    /**
      * The underlying rejection type
      */
    type E <: Throwable

    /**
      * The underlying rejection value
      */
    def value: E

    /**
      * Its json encoder
      */
    def encoder: Encoder.AsObject[E]

    /**
      * Its [[HttpResponseFields]] instance
      */
    def responseFields: HttpResponseFields[E]

    def status: StatusCode = responseFields.statusFrom(value)

    def headers: Seq[HttpHeader] = responseFields.headersFrom(value)

    def asJsonObject: JsonObject = encoder.encodeObject(value)
  }

  object ContextRejection {

    type Aux[E0 <: Throwable] = ContextRejection { type E = E0 }

    def apply[E0 <: Throwable: Encoder.AsObject: HttpResponseFields](v: E0): ContextRejection.Aux[E0] =
      new ContextRejection {
        override type E = E0

        override def value: E = v

        override def encoder: Encoder.AsObject[E] = implicitly[Encoder.AsObject[E]]

        override def responseFields: HttpResponseFields[E] = implicitly[HttpResponseFields[E]]
      }
  }

  /**
    * Create a fetch context instance from an [[Organizations]], [[Projects]] and [[Quotas]] instances
    */
  def apply(organizations: Organizations, projects: Projects, quotas: Quotas): FetchContext[ContextRejection] =
    apply(
      organizations.fetchActiveOrganization(_).void,
      projects.defaultApiMappings,
      projects.fetch,
      quotas
    )

  def apply(
      fetchActiveOrganization: Label => IO[Unit],
      dam: ApiMappings,
      fetchProject: ProjectRef => IO[ProjectResource],
      quotas: Quotas
  ): FetchContext[ContextRejection] =
    new FetchContext[ContextRejection] {

      override def defaultApiMappings: ApiMappings = dam

      override def onRead(ref: ProjectRef): IO[ProjectContext] =
        fetchProject(ref).attemptNarrow[ProjectRejection].flatMap {
          case Left(rejection)                                   => IO.raiseError(ContextRejection(rejection))
          case Right(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Right(project)                                    => IO.pure(project.value.context)
        }

      private def onWrite(ref: ProjectRef) =
        fetchProject(ref).attemptNarrow[ProjectRejection].flatMap {
          case Left(rejection)                                   => IO.raiseError(ContextRejection(rejection))
          case Right(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Right(project) if project.deprecated              => IO.raiseError(ProjectIsDeprecated(ref))
          case Right(project)                                    => IO.pure(project.value.context)
        }

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        quotas
          .reachedForResources(ref, subject)
          .adaptError { case e: QuotaRejection => ContextRejection(e) } >> onModify(ref)

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        for {
          _       <- fetchActiveOrganization(ref.organization).adaptError { case rejection: model.OrganizationRejection =>
                       ContextRejection(rejection)
                     }
          _       <- quotas
                       .reachedForEvents(ref, subject)
                       .adaptError { case e: QuotaRejection => ContextRejection(e) }
          context <- onWrite(ref)
        } yield context
    }
}
