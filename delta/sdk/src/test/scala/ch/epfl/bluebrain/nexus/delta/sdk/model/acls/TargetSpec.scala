package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TargetSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "A Target location for an ACL" should {
    val orgTarget  = Organization(org)
    val projTarget = Project(org, proj)

    "return its string representation" in {
      val list = List(Root -> "/", orgTarget -> "/org", projTarget -> "/org/proj")
      forAll(list) {
        case (target, expectedString) => target.string shouldEqual expectedString
      }
    }

    "return its parents" in {
      val list = List(Root -> None, orgTarget -> Some(Root), projTarget -> Some(orgTarget))
      forAll(list) {
        case (target, parent) => target.parent shouldEqual parent
      }
    }

    "apply on another target taking into account ancestors" in {
      val rootList = List(Root, orgTarget, projTarget).map(Root -> _)
      val orgList  = List(orgTarget, projTarget, Project(org, Label.unsafe("proj2"))).map(orgTarget -> _)
      val projList = List(projTarget).map(projTarget -> _)

      forAll(rootList ++ orgList ++ projList) {
        case (target, that) =>
          target.appliesWithAncestorsOn(that) shouldEqual true
      }
    }

    "not apply on another target taking into account ancestors" in {
      val org2     = Label.unsafe("org2")
      val orgList  = List(Root, Organization(org2), Project(org2, proj)).map(orgTarget -> _)
      val projList = List(Root, orgTarget, Project(org2, proj)).map(projTarget -> _)

      forAll(orgList ++ projList) {
        case (target, that) =>
          target.appliesWithAncestorsOn(that) shouldEqual false
      }
    }

  }
}
