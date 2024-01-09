package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.noopStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ScopeInitializer {

  /** Execute the actions necessary at org creation */
  def initializeOrganization(
      organizationResource: OrganizationResource
  )(implicit caller: Subject): IO[Unit]

  /** Execute the actions necessary at project creation */
  def initializeProject(
      project: ProjectRef
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
        scopeInitializations.toList
          .parFoldMapA { scope =>
            scope.onOrganizationCreation(organizationResource.value, caller).attempt
          }
          .flatMap(IO.fromEither)
          .adaptError { case e: ScopeInitializationFailed =>
            OrganizationInitializationFailed(e)
          }

      override def initializeProject(
          project: ProjectRef
      )(implicit caller: Subject): IO[Unit] = {
        scopeInitializations.toList
          .parFoldMapA { scope =>
            scope.onProjectCreation(project, caller).attempt.flatTap {
              case Left(e: ScopeInitializationFailed) => errorStore.save(scope.entityType, project, e)
              case _                                  => IO.unit
            }
          }
          .flatMap(IO.fromEither)
          .adaptError { case e: ScopeInitializationFailed =>
            ProjectInitializationFailed(e)
          }
      }

    }

  /** A constructor for tests that does not store initialization errors */
  def withoutErrorStore(
      scopeInitializations: Set[ScopeInitialization]
  ): ScopeInitializer =
    apply(scopeInitializations, noopStore)

  /** An initializer that does not perform any operation */
  def noop: ScopeInitializer = withoutErrorStore(Set.empty)
}
