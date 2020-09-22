package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclAddressSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "An ACL address" should {
    val orgAddress  = Organization(org)
    val projAddress = Project(org, proj)

    "return its string representation" in {
      val list = List(Root -> "/", orgAddress -> "/org", projAddress -> "/org/proj")
      forAll(list) {
        case (address, expectedString) => address.string shouldEqual expectedString
      }
    }

    "return its parents" in {
      val list = List(Root -> None, orgAddress -> Some(Root), projAddress -> Some(orgAddress))
      forAll(list) {
        case (address, parent) => address.parent shouldEqual parent
      }
    }
  }
}
