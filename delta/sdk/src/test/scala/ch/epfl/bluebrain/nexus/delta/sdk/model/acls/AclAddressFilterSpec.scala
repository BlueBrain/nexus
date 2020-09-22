package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclAddressFilterSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "An ACL address filter" should {
    val org2          = Label.unsafe("org2")
    val proj2         = Label.unsafe("proj2")
    val orgAddress    = Organization(org)
    val org2Address   = Organization(org2)
    val projAddress   = Project(org, proj)
    val proj12Address = Project(org, proj2)
    val proj22Address = Project(org2, proj2)
    val anyFilter     = AnyOrganizationAnyProject
    val anyOrgFilter  = AnyOrganization

    "return its string representation" in {
      val list = List(anyFilter -> "/*/*", anyOrgFilter -> "/*", AnyProject(org) -> "/org/*")
      forAll(list) {
        case (address, expectedString) => address.string shouldEqual expectedString
      }
    }

    "match an address" in {
      val list = List(
        anyOrgFilter    -> orgAddress,
        AnyProject(org) -> projAddress,
        AnyProject(org) -> proj12Address,
        anyFilter       -> projAddress,
        anyFilter       -> proj12Address,
        anyFilter       -> proj22Address
      )
      forAll(list) {
        case (filter, address) =>
          filter.matches(address) shouldEqual true
      }
    }

    "not match an address" in {
      val list = List(
        anyOrgFilter    -> AclAddress.Root,
        anyOrgFilter    -> projAddress,
        AnyProject(org) -> AclAddress.Root,
        AnyProject(org) -> orgAddress,
        AnyProject(org) -> proj22Address,
        anyFilter       -> AclAddress.Root,
        anyFilter       -> orgAddress
      )
      forAll(list) {
        case (filter, address) =>
          filter.matches(address) shouldEqual false
      }
    }

    "match an address and its ancestors" in {
      val list = List(
        anyOrgFilter    -> AclAddress.Root,
        anyOrgFilter    -> orgAddress,
        AnyProject(org) -> AclAddress.Root,
        AnyProject(org) -> orgAddress,
        AnyProject(org) -> projAddress,
        AnyProject(org) -> proj12Address,
        anyFilter       -> AclAddress.Root,
        anyFilter       -> orgAddress,
        anyFilter       -> org2Address,
        anyFilter       -> projAddress,
        anyFilter       -> proj12Address,
        anyFilter       -> proj22Address
      )
      forAll(list) {
        case (filter, address) =>
          filter.matchesWithAncestors(address) shouldEqual true
      }
    }

    "not match an address nor its ancestors" in {
      val list = List(
        anyOrgFilter    -> projAddress,
        AnyProject(org) -> org2Address,
        AnyProject(org) -> proj22Address
      )
      forAll(list) {
        case (filter, address) =>
          filter.matchesWithAncestors(address) shouldEqual false
      }
    }
  }
}
