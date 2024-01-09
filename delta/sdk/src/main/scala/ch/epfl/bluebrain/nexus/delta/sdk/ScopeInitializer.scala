package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import cats.effect.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.noopStore
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
        scopeInitializations
          .parUnorderedTraverse(_.onOrganizationCreation(organizationResource.value, caller))
          .adaptError { case e: ScopeInitializationFailed =>
            OrganizationInitializationFailed(e)
          }
          .void

      override def initializeProject(
          project: ProjectRef
      )(implicit caller: Subject): IO[Unit] = {
        completeAllAndRaiseFirstError(
          scopeInitializations,
          _.onProjectCreation(project, caller),
          errorStore.save(_, project, _)
        ).adaptError { case e: ScopeInitializationFailed =>
          ProjectInitializationFailed(e)
        }.void
      }

      /**
        * Execute all the provided initializations in parallel and raise the first error that occurs. The IOs still
        * running after the failure are not cancelled.
        * @param scopeInits
        *   the list of initializations to execute
        * @param fetchIO
        *   how to get the IO for each [[ScopeInitialization]]
        * @param onError
        *   what to do when a [[ScopeInitialization]] occurs
        */
      private def completeAllAndRaiseFirstError(
          scopeInits: Set[ScopeInitialization],
          fetchIO: ScopeInitialization => IO[Unit],
          onError: (EntityType, ScopeInitializationFailed) => IO[Unit]
      ): IO[Unit] =
        Ref.of[IO, Option[ScopeInitializationFailed]](None).flatMap { errorRef =>
          scopeInits.parUnorderedTraverse { init =>
            fetchIO(init).handleErrorWith { e =>
              e match {
                case e: ScopeInitializationFailed => onError(init.entityType, e) >> errorRef.set(Some(e))
                case _                            => IO.unit
              }
            }
          } >>
            errorRef.get.flatMap {
              case Some(value) => IO.raiseError(value)
              case None        => IO.unit
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
