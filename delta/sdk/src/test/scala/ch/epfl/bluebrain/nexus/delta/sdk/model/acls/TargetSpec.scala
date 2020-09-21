package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TargetSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "A Target location for an ACL" should {
    val proj2      = Label.unsafe("proj2")
    val orgTarget  = Organization(org)
    val projTarget = Project(org, proj)
    val any        = AnyOrganizationAnyProject
    val anyOrg     = AnyOrganization

    "return its string representation" in {
      val list = List(Root -> "/", orgTarget -> "/org", projTarget -> "/org/proj")
      forAll(list) {
        case (target, expectedString) => target.string shouldEqual expectedString
      }
    }

    "return its parents" in {
      val list = List(
        Root            -> None,
        orgTarget       -> Some(Root),
        projTarget      -> Some(orgTarget),
        any             -> Some(anyOrg),
        anyOrg          -> Some(Root),
        AnyProject(org) -> Some(orgTarget)
      )
      forAll(list) {
        case (target, parent) => target.parent shouldEqual parent
      }
    }

    "apply on another target" in {
      val rootList = List(Root -> Root)
      val orgList  = List(orgTarget, anyOrg).map(orgTarget -> _)
      val projList = List(projTarget, any, AnyProject(org)).map(projTarget -> _)

      forAll(rootList ++ orgList ++ projList) {
        case (target, that) =>
          target.appliesOn(that) shouldEqual true
      }
    }

    "not apply on another target" in {
      val org2     = Label.unsafe("org2")
      val rootList = List(orgTarget, projTarget, any, anyOrg, AnyProject(org)).map(Root -> _)
      val orgList  =
        List(Root, Organization(org2), Project(org2, proj), projTarget, Project(org, proj2), any, AnyProject(org))
          .map(orgTarget -> _)
      val projList = List(Root, orgTarget, Project(org2, proj)).map(projTarget -> _)

      forAll(rootList ++ orgList ++ projList) {
        case (target, that) =>
          target.appliesOn(that) shouldEqual false
      }
    }
  }
}
