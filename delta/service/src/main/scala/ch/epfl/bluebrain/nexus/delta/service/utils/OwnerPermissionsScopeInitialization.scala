package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of ACLs for newly created organizations and projects. It performs a noop if
  * executed during a migration.
  *
  * @param acls             the acls module
  * @param ownerPermissions the collection of permissions to be granted to the owner (creator)
  * @param serviceAccount   the subject that will be recorded when performing the initialization
  */
class OwnerPermissionsScopeInitialization(acls: Acls, ownerPermissions: Set[Permission], serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger          = Logger[OwnerPermissionsScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  override def onOrganizationCreation(
      organization: Organization,
      subject: Subject
  ): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) IO.unit
    else
      acls
        .fetch(AclAddress.Organization(organization.label))
        .void // discard value as the check is only done to see if there have been ACLs already set on this path
        .onErrorHandleWith { _: AclRejection.AclNotFound =>
          val str = s"Owner permissions for organization '${organization.label}' have not been set, applying them now."
          UIO.delay(logger.info(str)) >>
            acls
              .append(Acl(AclAddress.Organization(organization.label), subject -> ownerPermissions), 0L)
              .void // discard return value
              .onErrorHandleWith {
                case _: AclRejection.IncorrectRev => // concurrent update to the acls of the organization, assuming a success
                  IO.unit
                case rej                          =>
                  val str =
                    s"Failed to apply the owner permissions for organization '${organization.label}' due to '${rej.reason}'."
                  UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
              }
        }
        .named("setOwnerPermissions", Organizations.moduleType)

  override def onProjectCreation(project: Project, subject: Subject): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) IO.unit
    else
      acls
        .fetch(AclAddress.Project(project.ref))
        .void // discard value as the check is only done to see if there have been ACLs already set on this path
        .onErrorHandleWith { _: AclRejection.AclNotFound =>
          val str = s"Owner permissions for project '${project.ref}' have not been set, applying them now."
          UIO.delay(logger.info(str)) >>
            acls
              .append(Acl(AclAddress.Project(project.ref), subject -> ownerPermissions), 0L)
              .void // discard return value
              .onErrorHandleWith {
                case _: AclRejection.IncorrectRev => // concurrent update to the acls of the project, assuming a success
                  IO.unit
                case rej                          =>
                  val str =
                    s"Failed to apply the owner permissions for project '${project.ref}' due to '${rej.reason}'."
                  UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
              }
        }
        .named("setOwnerPermissions", Projects.moduleType)
}
