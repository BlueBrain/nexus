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
    val anyFilter     = AnyOrganizationAnyProject(false)
    val anyOrgFilter  = AnyOrganization(false)

    "return its string representation" in {
      val list = List(anyFilter -> "/*/*", anyOrgFilter -> "/*", AnyProject(org, withAncestors = false) -> "/org/*")
      forAll(list) { case (address, expectedString) =>
        address.string shouldEqual expectedString
      }
    }

    "match an address" in {
      val list = List(
        anyOrgFilter                           -> orgAddress,
        AnyProject(org, withAncestors = false) -> projAddress,
        AnyProject(org, withAncestors = false) -> proj12Address,
        anyFilter                              -> projAddress,
        anyFilter                              -> proj12Address,
        anyFilter                              -> proj22Address
      )
      forAll(list) { case (filter, address) =>
        filter.matches(address) shouldEqual true
      }
    }

    "not match an address" in {
      val list = List(
        anyOrgFilter                           -> AclAddress.Root,
        anyOrgFilter                           -> projAddress,
        AnyProject(org, withAncestors = false) -> AclAddress.Root,
        AnyProject(org, withAncestors = false) -> orgAddress,
        AnyProject(org, withAncestors = false) -> proj22Address,
        anyFilter                              -> AclAddress.Root,
        anyFilter                              -> orgAddress
      )
      forAll(list) { case (filter, address) =>
        filter.matches(address) shouldEqual false
      }
    }

    "match an address and its ancestors" in {
      val anyFilterWithAncestors    = AnyOrganizationAnyProject(true)
      val anyOrgFilterWithAncestors = AnyOrganization(true)
      val list                      = List(
        anyOrgFilterWithAncestors             -> AclAddress.Root,
        anyOrgFilterWithAncestors             -> orgAddress,
        AnyProject(org, withAncestors = true) -> AclAddress.Root,
        AnyProject(org, withAncestors = true) -> orgAddress,
        AnyProject(org, withAncestors = true) -> projAddress,
        AnyProject(org, withAncestors = true) -> proj12Address,
        anyFilterWithAncestors                -> AclAddress.Root,
        anyFilterWithAncestors                -> orgAddress,
        anyFilterWithAncestors                -> org2Address,
        anyFilterWithAncestors                -> projAddress,
        anyFilterWithAncestors                -> proj12Address,
        anyFilterWithAncestors                -> proj22Address
      )
      forAll(list) { case (filter, address) =>
        filter.matches(address) shouldEqual true
      }
    }

    "not match an address nor its ancestors" in {
      val list = List(
        anyOrgFilter           -> projAddress,
        AnyProject(org, false) -> org2Address,
        AnyProject(org, false) -> proj22Address
      )
      forAll(list) { case (filter, address) =>
        filter.matches(address) shouldEqual false
      }
    }
  }
}
