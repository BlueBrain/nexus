package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclTargetsSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "AclTargets" should {
    val instant = Instant.EPOCH

    val types  = Set(nxv.AccessControlList)
    val schema = Latest(schemas.acls)

    val userRW_groupX    = Acl(user -> Set(r, w), group -> Set(x))
    val acl: AclResource = ResourceF(Root, 1L, types, false, instant, user, instant, anon, schema, userRW_groupX)

    val groupR            = Acl(group -> Set(r))
    val acl2: AclResource = ResourceF(Root, 2L, types, false, instant, user, instant, user, schema, groupR)

    val groupX            = Acl(group -> Set(x))
    val acl3: AclResource = ResourceF(Root, 3L, types, false, instant, user, instant, anon, schema, groupX)

    "be merged with other AclTargets" in {
      val acls1    = AclTargets(acl)
      val acls2    = AclTargets(acl2, acl3.copy(id = Project(org, proj)))
      val expected = AclTargets(acl.as(Acl(user -> Set(r, w), group -> Set(r, x))), acl3.copy(id = Project(org, proj)))

      acls1 ++ acls2 shouldEqual expected

      acls1 + acl2 + acl3.copy(id = Project(org, proj)) shouldEqual expected

    }

    "filter identities" in {
      val acls = AclTargets(acl.copy(id = Project(org, proj)), acl2.copy(id = Organization(org)), acl3)
      acls.filter(Set(user)) shouldEqual
        AclTargets(
          acl3.as(Acl.empty),
          acl2.copy(id = Organization(org), value = Acl.empty),
          acl.copy(id = Project(org, proj), value = Acl(user -> Set(r, w)))
        )
      acls.filter(Set(user, group)) shouldEqual acls
    }

    "remove empty ACL" in {
      val proj2 = Label.unsafe("proj2")
      val acls  = AclTargets(
        acl.copy(id = Project(org, proj)),
        acl.as(Acl.empty),
        acl.copy(id = Project(org, proj2), value = Acl(user -> Set(r, w), group -> Set.empty)),
        acl.copy(id = Organization(org), value = Acl(user -> Set.empty, group -> Set.empty))
      )
      acls.removeEmpty() shouldEqual
        AclTargets(
          acl.copy(id = Project(org, proj)),
          acl.copy(id = Project(org, proj2), value = Acl(user -> Set(r, w)))
        )
    }

    "check for matching identities and permission on a Root target" in {
      val acls = AclTargets(acl)

      forAll(List(Project(org, proj), Organization(org), Root)) { target =>
        acls.exists(Set(user, anon), r, target) shouldEqual true
        acls.exists(Set(anon), r, target) shouldEqual false
        acls.exists(Set(user, anon), x, target) shouldEqual false
      }

    }

    "check for matching identities and permission on a Project target" in {
      val acls  = AclTargets(acl.copy(id = Project(org, proj)): AclResource)
      val proj2 = Label.unsafe("proj2")

      forAll(List(Project(org, proj2), Organization(org), Root)) { target =>
        acls.exists(Set(user, anon), r, target) shouldEqual false
      }
      acls.exists(Set(user, anon), r, Project(org, proj)) shouldEqual true
      acls.exists(Set(anon), r, Project(org, proj)) shouldEqual false
      acls.exists(Set(user, anon), x, Project(org, proj)) shouldEqual false
    }
  }

}
