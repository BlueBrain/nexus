package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}

trait ScopeInitializer {

  /** Execute the actions necessary at org creation */
  def initializeOrganization(
      organizationResource: OrganizationResource
  )(implicit caller: Subject): IO[Unit]

  /** Execute the actions necessary at project creation */
  def initializeProject(
      projectResource: ProjectResource
  )(implicit caller: Subject): IO[Unit]

}

object ScopeInitializer {

  /**
    * Construct a [[ScopeInitializer]] out of the provided [[ScopeInitialization]]
    *
    * @param scopeInitializations
    *   the set of initializations to execute
    * @param errorStore
    *   the store for initialization errors
    * @return
    */
  def apply(
      scopeInitializations: Set[ScopeInitialization],
      errorStore: => ScopeInitializationErrorStore
  ): ScopeInitializer =
    new ScopeInitializer {

      override def initializeOrganization(
          organizationResource: OrganizationResource
      )(implicit caller: Subject): IO[Unit] =
        scopeInitializations
          .parUnorderedTraverse(_.onOrganizationCreation(organizationResource.value, caller))
          .adaptError { case e: ScopeInitializationFailed =>
            OrganizationInitializationFailed(e)
          }
          .void

      override def initializeProject(
          projectResource: ProjectResource
      )(implicit caller: Subject): IO[Unit] =
        scopeInitializations
          .parUnorderedTraverse { init =>
            init
              .onProjectCreation(projectResource.value, caller)
              .onError {
                case e: ScopeInitializationFailed =>
                  errorStore.save(init.entityType, projectResource.value.ref, e)
                case _                            => IO.unit
              }
          }
          .adaptError { case e: ScopeInitializationFailed => ProjectInitializationFailed(e) }
          .void

    }

  /** A constructor for tests that does not store initialization errors */
  def withoutErrorStore(
      scopeInitializations: Set[ScopeInitialization]
  ): ScopeInitializer = {
    val dummyErrorStore = new ScopeInitializationErrorStore {
      override def save(entityType: EntityType, ref: ProjectRef, error: ScopeInitializationFailed): IO[Unit] = IO.unit
      override def fetch: IO[List[ScopeInitErrorRow]]                                                        = IO.pure(List.empty)
    }
    apply(scopeInitializations, dummyErrorStore)
  }
}
