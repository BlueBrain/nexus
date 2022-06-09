package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, ScopeInitialization}
import monix.bio.IO

/**
  * Apply the owner permissions at org/project creation
  *
  * @param acls
  *   an instance of the acl module
  * @param ownerPermissions
  *   the owner permissions to be present at project creation
  * @param serviceAccount
  *   the service account to apply owner permissions when needed
  */
class OwnerPermissionsDummy(acls: Acls, ownerPermissions: Set[Permission], serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  implicit private val caller: Caller = serviceAccount.caller

  override def onOrganizationCreation(
      organization: Organization,
      subject: Subject
  ): IO[ScopeInitializationFailed, Unit] = {
    applyOwnerPermissions(AclAddress.Organization(organization.label), subject)
  }

  override def onProjectCreation(project: Project, subject: Subject): IO[ScopeInitializationFailed, Unit] =
    applyOwnerPermissions(AclAddress.Project(project.ref), subject)

  private def applyOwnerPermissions(address: AclAddress, subject: Subject): IO[ScopeInitializationFailed, Unit] =
    IO.when(ownerPermissions.nonEmpty)(
      acls
        .append(Acl(address, subject -> ownerPermissions), 0L)
        .void
        .onErrorHandle(_ => ()) // if there's a conflict permissions have already been set, ignore
    )
}

object OwnerPermissionsDummy {

  /**
    * Create a new instance of [[OwnerPermissionsDummy]]
    *
    * @param acls
    *   an acls instance
    * @param ownerPermissions
    *   the owner permissions to be present on the project/org
    * @param serviceAccount
    *   the service account to apply the acls if needed
    */
  def apply(acls: Acls, ownerPermissions: Set[Permission], serviceAccount: ServiceAccount): OwnerPermissionsDummy =
    new OwnerPermissionsDummy(acls, ownerPermissions, serviceAccount)
}
