package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclAddressSpec extends AnyWordSpecLike with Matchers with AclFixtures with TestHelpers {

  "An ACL address" should {
    val orgAddress  = Organization(org)
    val projAddress = Project(org, proj)

    "return its string representation" in {
      val list = List(Root -> "/", orgAddress -> "/org", projAddress -> "/org/proj")
      forAll(list) { case (address, expectedString) =>
        address.string shouldEqual expectedString
      }
    }

    "return its parents" in {
      val list = List(Root -> None, orgAddress -> Some(Root), projAddress -> Some(orgAddress))
      forAll(list) { case (address, parent) =>
        address.parent shouldEqual parent
      }
    }

    "be constructed correctly from string" in {
      val list = List(Root -> "/", orgAddress -> "/org", projAddress -> "/org/proj")
      forAll(list) { case (address, string) =>
        AclAddress.fromString(string).rightValue shouldEqual address
      }
    }

    "fail to be constructed from string" in {
      val list = List("", "//", "/asd!", "/asd/a!", "/a/", s"/${genString(length = 37, 'a' to 'z')}")
      forAll(list) { string =>
        AclAddress.fromString(string).leftValue
      }
    }
  }
}
