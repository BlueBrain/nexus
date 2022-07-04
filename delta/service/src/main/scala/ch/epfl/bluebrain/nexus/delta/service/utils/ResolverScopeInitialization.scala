package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{ResourceAlreadyExists, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Resolvers, ScopeInitialization}
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

  private val logger: Logger                               = Logger[ResolverScopeInitialization]
  private val defaultInProjectResolverValue: ResolverValue = InProjectValue(Priority.unsafe(1))
  implicit private val caller: Caller                      = serviceAccount.caller

  override def onProjectCreation(project: Project, subject: Subject): IO[ScopeInitializationFailed, Unit] =
    resolvers
      .create(nxv.defaultResolver, project.ref, defaultInProjectResolverValue)
      .void
      .onErrorHandleWith {
        case _: ResourceAlreadyExists        => UIO.unit // nothing to do, resolver already exits
        case _: WrappedProjectRejection      => UIO.unit // project is likely deprecated
        case _: WrappedOrganizationRejection => UIO.unit // org is likely deprecated
        case rej                             =>
          val str =
            s"Failed to create the default InProject resolver for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createDefaultResolver", Resolvers.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit
}
