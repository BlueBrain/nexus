package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

/**
  * Apply the owner permissions at org/project creation
  *
  * @param acls               an instance of the acl module
  * @param ownerPermissions   the owner permissions to be present at project creation
  * @param serviceAccount     the service account to apply owner permissions when needed
  */
class ApplyOwnerPermissions private (acls: Acls, ownerPermissions: Set[Permission], serviceAccount: Identity.Subject) {

  /**
    * Apply owner permissions for an organization
    *
    * @param label   the label of the organization
    * @param subject the organization owner
    */
  def onOrganization(label: Label, subject: Identity.Subject): IO[AclRejection, Unit] =
    applyOwnerPermissions(AclAddress.Organization(label), subject)

  /**
    * Apply owner permissions for an organization
    *
    * @param ref     the reference of the project
    * @param subject the organization owner
    */
  def onProject(ref: ProjectRef, subject: Identity.Subject): IO[AclRejection, Unit] =
    applyOwnerPermissions(AclAddress.Project(ref.organization, ref.project), subject)

  private def applyOwnerPermissions(address: AclAddress, subject: Identity.Subject): IO[AclRejection, Unit] = {
    def applyMissing(collection: AclCollection) = {
      val currentPermissions =
        collection.filter(Set(subject)).value.foldLeft(Set.empty[Permission]) { case (acc, (_, acl)) =>
          acc ++ acl.value.permissions
        }

      if (ownerPermissions.subsetOf(currentPermissions))
        IO.unit
      else {
        val rev = collection.value.get(address).fold(0L)(_.rev)
        acls.append(Acl(address, subject -> ownerPermissions), rev)(serviceAccount) >> IO.unit
      }
    }

    acls.fetchWithAncestors(address).flatMap(applyMissing)
  }
}

object ApplyOwnerPermissions {

  /**
    * Apply the owner permissions at org/project creation
    *
    * @param acls               an instance of the acl module
    * @param ownerPermissions   the owner permissions to be present at project creation
    * @param serviceAccount     the service account to apply owner permissions when needed
    */
  def apply(acls: Acls, ownerPermissions: Set[Permission], serviceAccount: Identity.Subject): ApplyOwnerPermissions =
    new ApplyOwnerPermissions(acls, ownerPermissions, serviceAccount)

}
