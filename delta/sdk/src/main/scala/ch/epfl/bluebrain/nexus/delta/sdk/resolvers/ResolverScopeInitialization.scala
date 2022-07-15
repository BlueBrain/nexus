package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{ProjectContextRejection, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the InProject resolver as part of the project initialization.
  *
  * @param resolvers
  *   the resolvers module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class ResolverScopeInitialization(resolvers: Resolvers, serviceAccount: ServiceAccount) extends ScopeInitialization {

  private val logger: Logger                                = Logger[ResolverScopeInitialization]
  private val defaultInProjectResolverValue: ResolverValue  = InProjectValue(Priority.unsafe(1))
  implicit private val caller: Caller                       = serviceAccount.caller
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def onProjectCreation(project: Project, subject: Subject): IO[ScopeInitializationFailed, Unit] =
    resolvers
      .create(nxv.defaultResolver, project.ref, defaultInProjectResolverValue)
      .void
      .onErrorHandleWith {
        case _: ResourceAlreadyExists   => UIO.unit // nothing to do, resolver already exits
        case _: ProjectContextRejection => UIO.unit // project or org is likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the default InProject resolver for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultResolver")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit
}
