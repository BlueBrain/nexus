package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclTargetsSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "AclTargets" should {
    val instant = Instant.EPOCH
    val base    = "http://example.com"

    val types  = Set(nxv.AccessControlList)
    val schema = Latest(schemas.acls)

    val userRW_groupX = Acl(user -> Set(r, w), group -> Set(x))
    val acl           = ResourceF(iri"$base/id1", 1L, types, false, instant, user, instant, anon, schema, userRW_groupX)

    val groupR = Acl(group -> Set(r))
    val acl2   = ResourceF(iri"$base/id2", 2L, types, false, instant, user, instant, user, schema, groupR)

    val groupX = Acl(group -> Set(x))
    val acl3   = ResourceF(iri"$base/id3", 3L, types, false, instant, user, instant, anon, schema, groupX)

    "be merged with other AclTargets" in {
      val acls1    = AclTargets(Root -> acl)
      val acls2    = AclTargets(Root -> acl2, Project(org, proj) -> acl3)
      val expected = AclTargets(
        Root               -> acl.as(Acl(user -> Set(r, w), group -> Set(r, x))),
        Project(org, proj) -> acl3
      )

      acls1 ++ acls2 shouldEqual expected

      acls1 + (Root -> acl2) + (Project(org, proj) -> acl3) shouldEqual expected

    }

    "filter identities" in {
      val acls = AclTargets(Project(org, proj) -> acl, Organization(org) -> acl2, Root -> acl3)
      acls.filter(Set(user)) shouldEqual
        AclTargets(
          Root               -> acl3.as(Acl.empty),
          Organization(org)  -> acl2.as(Acl.empty),
          Project(org, proj) -> acl.as(Acl(user -> Set(r, w)))
        )
      acls.filter(Set(user, group)) shouldEqual acls
    }

    "remove empty ACL" in {
      val proj2 = Label.unsafe("proj2")
      val acls  = AclTargets(
        Project(org, proj)  -> acl,
        Root                -> acl.as(Acl.empty),
        Project(org, proj2) -> acl.as(Acl(user -> Set(r, w), group -> Set.empty)),
        Organization(org)   -> acl.as(Acl(user -> Set.empty, group -> Set.empty))
      )
      acls.removeEmpty() shouldEqual
        AclTargets(
          Project(org, proj)  -> acl,
          Project(org, proj2) -> acl.as(Acl(user -> Set(r, w)))
        )
    }

    "check for matching identities and permission on a Root target" in {
      val acls = AclTargets(Root -> acl)

      forAll(List(Project(org, proj), Organization(org), Root)) { target =>
        acls.exists(Set(user, anon), r, target) shouldEqual true
        acls.exists(Set(anon), r, target) shouldEqual false
        acls.exists(Set(user, anon), x, target) shouldEqual false
      }

    }

    "check for matching identities and permission on a Project target" in {
      val acls  = AclTargets(Project(org, proj) -> acl)
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
