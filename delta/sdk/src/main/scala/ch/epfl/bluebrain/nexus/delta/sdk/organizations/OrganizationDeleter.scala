package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNonEmpty
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope.Org
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.DatabasePartitioner

trait OrganizationDeleter {
  def apply(org: Label): IO[Unit]
}

object OrganizationDeleter {

  def apply(
      acls: Acls,
      orgs: Organizations,
      projects: Projects,
      databasePartitioner: DatabasePartitioner
  ): OrganizationDeleter = {
    def hasAnyProject(org: Label): IO[Boolean] =
      projects.currentRefs(Org(org)).find(_.organization == org).compile.last.map(_.isDefined)
    def compiledDeletionTask(org: Label)       =
      acls.purge(AclAddress.fromOrg(org)) >> databasePartitioner.onDeleteOrg(org) >> orgs.purge(org)
    apply(hasAnyProject, compiledDeletionTask)
  }

  def apply(hasAnyProject: Label => IO[Boolean], deletionTask: Label => IO[Unit]): OrganizationDeleter =
    new OrganizationDeleter {

      private val logger = Logger[OrganizationDeleter]

      def apply(org: Label): IO[Unit] =
        hasAnyProject(org).flatMap {
          case true  =>
            logger.error(s"Failed to delete empty organization $org") >> IO.raiseError(OrganizationNonEmpty(org))
          case false =>
            logger.info(s"Deleting empty organization $org") >> deletionTask(org)
        }
    }
}
