package ch.epfl.bluebrain.nexus.delta.sdk.projects

import akka.http.scaladsl.model.{HttpHeader, StatusCode}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.{Encoder, JsonObject}
import monix.bio.{IO, UIO}

import scala.collection.concurrent

/**
  * Define the rules to fetch project context for read and write operations
  */
trait FetchContext[R] { self =>

  /**
    * The default api mappings
    */
  def defaultApiMappings: ApiMappings

  /**
    * Fetch a context for a read operation
    * @param ref
    *   the project to fetch the context from
    */
  def onRead(ref: ProjectRef): IO[R, ProjectContext]

  /**
    * Fetch context for a create operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[R, ProjectContext]

  /**
    * Fetch context for a modify operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onModify(ref: ProjectRef)(implicit subject: Subject): IO[R, ProjectContext]

  /**
    * Cache onRead operations to avoid unnecessary queries during batch operations like listings
    * @return
    *   a new instance caching onRead calls
    */
  def cacheOnReads: FetchContext[R] = new FetchContext[R] {

    private val cache: concurrent.Map[ProjectRef, ProjectContext] = new concurrent.TrieMap

    override def defaultApiMappings: ApiMappings = self.defaultApiMappings

    override def onRead(ref: ProjectRef): IO[R, ProjectContext] =
      IO.fromOption(cache.get(ref)).onErrorFallbackTo(self.onRead(ref).tapEval { pc => UIO.delay(cache.put(ref, pc)) })

    override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[R, ProjectContext] = self.onCreate(ref)

    override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[R, ProjectContext] = self.onCreate(ref)
  }

  /**
    * Map the rejection to another one
    * @param f
    *   the function from [[R]] to [[R2]]
    */
  def mapRejection[R2](f: R => R2): FetchContext[R2] =
    new FetchContext[R2] {
      override def defaultApiMappings: ApiMappings = self.defaultApiMappings

      override def onRead(ref: ProjectRef): IO[R2, ProjectContext] = self.onRead(ref).mapError(f)

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[R2, ProjectContext] =
        self.onCreate(ref).mapError(f)

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[R2, ProjectContext] =
        self.onModify(ref).mapError(f)
    }

}

object FetchContext {

  /**
    * A rejection allowing to align the different possible rejection when fetching a context
    */
  sealed trait ContextRejection {

    /**
      * The underlying rejection type
      */
    type E

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

    type Aux[E0] = ContextRejection { type E = E0 }

    def apply[E0: Encoder.AsObject: HttpResponseFields](v: E0): ContextRejection.Aux[E0] = new ContextRejection {
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
    apply(organizations.fetchActiveOrganization(_).void, projects.defaultApiMappings, projects.fetch, quotas)

  def apply(
      fetchActiveOrganization: Label => IO[OrganizationRejection, Unit],
      dam: ApiMappings,
      fetchProject: ProjectRef => IO[ProjectNotFound, ProjectResource],
      quotas: Quotas
  ): FetchContext[ContextRejection] =
    new FetchContext[ContextRejection] {

      override def defaultApiMappings: ApiMappings = dam

      override def onRead(ref: ProjectRef): IO[ContextRejection, ProjectContext] =
        fetchProject(ref)
          .tapEval { p =>
            IO.raiseWhen(p.value.markedForDeletion)(ProjectIsMarkedForDeletion(ref))
          }
          .bimap(ContextRejection(_), _.value.context)

      private def onWrite(ref: ProjectRef) =
        fetchProject(ref)
          .tapEval { p =>
            IO.raiseWhen(p.value.markedForDeletion)(ProjectIsMarkedForDeletion(ref)) >>
              IO.raiseWhen(p.deprecated)(ProjectIsDeprecated(ref))
          }
          .bimap(ContextRejection(_), _.value.context)

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ContextRejection, ProjectContext] =
        quotas
          .reachedForResources(ref, subject)
          .leftWiden[QuotaRejection]
          .mapError[ContextRejection](e => ContextRejection(e)) >>
          onModify(ref)

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ContextRejection, ProjectContext] =
        for {
          _       <- fetchActiveOrganization(ref.organization).mapError(ContextRejection.apply(_))
          _       <- quotas.reachedForEvents(ref, subject).leftWiden[QuotaRejection].mapError(ContextRejection.apply(_))
          context <- onWrite(ref)
        } yield context
    }
}
