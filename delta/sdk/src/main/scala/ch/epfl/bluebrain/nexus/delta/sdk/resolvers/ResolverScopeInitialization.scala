package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverScopeInitialization.{logger, CreateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}

/**
  * The default creation of the InProject resolver as part of the project initialization.
  *
  * @param createResolver
  *   function used to create a resolver
  * @param defaults
  *   default name and description for the resolver
  */
class ResolverScopeInitialization(createResolver: CreateResolver, defaults: Defaults) extends ScopeInitialization {

  private val defaultInProjectResolverValue: ResolverValue  =
    InProjectValue(Some(defaults.name), Some(defaults.description), Priority.unsafe(1))
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def onProjectCreation(project: ProjectRef, subject: Subject): IO[Unit] =
    createResolver(project, defaultInProjectResolverValue)
      .handleErrorWith {
        case _: ResourceAlreadyExists => IO.unit // nothing to do, resolver already exits
        case rej                      =>
          val str =
            s"Failed to create the default InProject resolver for project '$project' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultResolver")

  override def onOrganizationCreation(organization: Organization, subject: Subject): IO[Unit] = IO.unit

  override def entityType: EntityType = Resolvers.entityType
}

object ResolverScopeInitialization {

  type CreateResolver = (ProjectRef, ResolverValue) => IO[Unit]

  private val logger = Logger[ResolverScopeInitialization]

  def apply(resolvers: Resolvers, serviceAccount: ServiceAccount, defaults: Defaults) = {
    implicit val caller: Caller        = serviceAccount.caller
    def createResolver: CreateResolver = resolvers.create(nxv.defaultResolver, _, _).void
    new ResolverScopeInitialization(
      createResolver,
      defaults
    )
  }

}
