package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclCollectionSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "A Collection of ACL" should {

    val orgAddress  = Organization(org)
    val projAddress = Project(org, proj)

    def acl(address: AclAddress): AclResource  = AclGen.resourceFor(userRW_groupX(address), 1L, subject)
    def acl2(address: AclAddress): AclResource = AclGen.resourceFor(groupR(address), 2L, subject)
    def acl3(address: AclAddress): AclResource = AclGen.resourceFor(groupX(address), 3L, subject)

    "be merged with other ACL collection" in {
      val acls1    = AclCollection(acl(Root))
      val acl3Proj = acl3(projAddress)
      val acls2    = AclCollection(acl2(Root), acl3Proj)
      val expected = AclCollection(acl2(Root).as(Acl(Root, subject -> Set(r, w), group -> Set(r, x))), acl3Proj)

      acls1 ++ acls2 shouldEqual expected
      acls1 + acl2(Root) + acl3Proj shouldEqual expected
    }

    "filter identities" in {
      val acls = AclCollection(acl(projAddress), acl2(orgAddress), acl3(Root))
      acls.filter(Set(subject)) shouldEqual
        AclCollection(
          acl3(Root).as(Acl(Root)),
          acl2(orgAddress).as(Acl(orgAddress)),
          acl(projAddress).as(Acl(projAddress, subject -> Set(r, w)))
        )
      acls.filter(Set(subject, group)) shouldEqual acls
    }

    "filter identities by permission" in {
      val acls = AclCollection(acl(projAddress), acl2(orgAddress), acl3(Root))
      acls.filterByPermission(Set(subject), r) shouldEqual AclCollection.empty + acl(projAddress)

    }

    "subtract an ACL" in {
      (AclCollection(acl(Root)) - acl3(Root)) shouldEqual AclCollection(acl3(Root).as(userRW(Root)))
      (AclCollection(acl(Root)) - acl(Root)) shouldEqual AclCollection.empty
    }

    "subtract an address" in {
      val acls = AclCollection(acl2(Root), acl3(projAddress))
      (acls - projAddress) shouldEqual AclCollection(acl2(Root))
    }

    "remove empty ACL" in {
      val proj2 = Label.unsafe("proj2")
      val acls  = AclCollection(
        acl(projAddress),
        acl(Root).as(Acl(Root)),
        acl(Project(org, proj2)).as(Acl(Project(org, proj2), subject -> Set(r, w), group -> Set.empty)),
        acl(orgAddress).as(Acl(orgAddress, subject -> Set.empty, group -> Set.empty))
      )
      acls.removeEmpty() shouldEqual
        AclCollection(
          acl(projAddress),
          acl(Project(org, proj2)).as(Acl(Project(org, proj2), subject -> Set(r, w)))
        )
    }

    "check for matching identities and permission on a Root address" in {
      val acls = AclCollection(acl(Root))

      forAll(List(projAddress, orgAddress, Root)) { address =>
        acls.exists(Set(subject, anon), r, address) shouldEqual true
        acls.exists(Set(anon), r, address) shouldEqual false
        acls.exists(Set(subject, anon), x, address) shouldEqual false
      }
    }

    "check for matching identities and permission on a Project address" in {
      val acls  = AclCollection(acl(projAddress): AclResource)
      val proj2 = Label.unsafe("proj2")

      forAll(List(Project(org, proj2), orgAddress, Root)) { address =>
        acls.exists(Set(subject, anon), r, address) shouldEqual false
      }
      acls.exists(Set(subject, anon), r, projAddress) shouldEqual true
      acls.exists(Set(anon), r, projAddress) shouldEqual false
      acls.exists(Set(subject, anon), x, projAddress) shouldEqual false
    }

    "fetch ACLs from given filter" in {
      val org2          = Label.unsafe("org2")
      val proj2         = Label.unsafe("proj2")
      val org2Address   = Organization(org2)
      val proj12Address = Project(org, proj2)
      val proj22Address = Project(org2, proj2)
      val any           = AnyOrganizationAnyProject(false)
      val anyOrg        = AnyOrganization(false)

      val orgAcl: AclResource    = acl2(orgAddress)
      val org2Acl: AclResource   = acl3(org2Address)
      val projAcl: AclResource   = acl2(projAddress)
      val proj12Acl: AclResource = acl3(proj12Address)
      val proj22Acl: AclResource = acl(proj22Address)
      val acls                   = AclCollection(acl(Root), orgAcl, org2Acl, projAcl, proj12Acl, proj22Acl)

      acls.fetch(any) shouldEqual AclCollection(projAcl, proj12Acl, proj22Acl)
      acls.fetch(AnyProject(org, withAncestors = false)) shouldEqual AclCollection(projAcl, proj12Acl)
      acls.fetch(anyOrg) shouldEqual AclCollection(orgAcl, org2Acl)
    }

    "fetch ACLs from given filter including the ancestor addresses" in {
      val org2          = Label.unsafe("org2")
      val proj2         = Label.unsafe("proj2")
      val org2Address   = Organization(org2)
      val proj12Address = Project(org, proj2)
      val proj22Address = Project(org2, proj2)
      val any           = AnyOrganizationAnyProject(true)
      val anyOrg        = AnyOrganization(true)

      val orgAcl: AclResource    = acl2(orgAddress)
      val org2Acl: AclResource   = acl3(org2Address)
      val projAcl: AclResource   = acl2(projAddress)
      val proj12Acl: AclResource = acl3(proj12Address)
      val proj22Acl: AclResource = acl(proj22Address)
      val acls                   = AclCollection(acl(Root), orgAcl, org2Acl, projAcl, proj12Acl, proj22Acl)

      acls.fetch(any) shouldEqual acls
      acls.fetch(AnyProject(org, withAncestors = true)) shouldEqual AclCollection(acl(Root), orgAcl, projAcl, proj12Acl)
      acls.fetch(anyOrg) shouldEqual AclCollection(acl(Root), orgAcl, org2Acl)
    }
  }

}
