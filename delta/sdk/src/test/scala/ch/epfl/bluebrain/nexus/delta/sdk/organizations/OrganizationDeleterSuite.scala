package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO
import cats.effect.kernel.Ref
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNonEmpty
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class OrganizationDeleterSuite extends NexusSuite {

  private val org1 = Label.unsafe("org1")
  private val org2 = Label.unsafe("org2")

  private def hasAnyProject(label: Label) = IO.pure(label == org1)

  private val deletedOrgs           = Ref.unsafe[IO, Set[Label]](Set.empty)
  private def deleteOrg(org: Label) = deletedOrgs.update(_ + org)

  private lazy val orgDeleter = OrganizationDeleter(hasAnyProject, deleteOrg)

  test("Fail when trying to delete a non-empty organization") {
    orgDeleter(org1).interceptEquals(OrganizationNonEmpty(org1)) >>
      deletedOrgs.get.assert(!_.contains(org1))
  }

  test("Successfully delete an empty organization") {
    orgDeleter(org2).assert >>
      deletedOrgs.get.assert(_.contains(org2))
  }
}
