package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{ProjectContextRejection, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

/**
  * The default creation of the InProject resolver as part of the project initialization.
  *
  * @param resolvers
  *   the resolvers module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class ResolverScopeInitialization(
    resolvers: Resolvers,
    serviceAccount: ServiceAccount,
    defaults: Defaults
) extends ScopeInitialization {

  private val logger                                        = Logger.cats[ResolverScopeInitialization]
  private val defaultInProjectResolverValue: ResolverValue  =
    InProjectValue(Some(defaults.name), Some(defaults.description), Priority.unsafe(1))
  implicit private val caller: Caller                       = serviceAccount.caller
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def onProjectCreation(project: Project, subject: Subject): IO[Unit] =
    resolvers
      .create(nxv.defaultResolver, project.ref, defaultInProjectResolverValue)
      .void
      .handleErrorWith {
        case _: ResourceAlreadyExists   => IO.unit // nothing to do, resolver already exits
        case _: ProjectContextRejection => IO.unit // project or org is likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the default InProject resolver for project '${project.ref}' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultResolver")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Subject
  ): IO[Unit] = IO.unit
}
