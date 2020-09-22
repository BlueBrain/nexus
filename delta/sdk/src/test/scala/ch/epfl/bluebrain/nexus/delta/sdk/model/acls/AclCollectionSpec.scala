package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclCollectionSpec extends AnyWordSpecLike with Matchers with AclFixtures {

  "A Collection of ACL" should {
    val types  = Set(nxv.AccessControlList)
    val schema = Latest(schemas.acls)

    val orgAddress  = Organization(org)
    val projAddress = Project(org, proj)

    val acl: AclResource  = ResourceF(Root, 1L, types, false, epoch, user, epoch, anon, schema, userRW_groupX)
    val acl2: AclResource = ResourceF(Root, 2L, types, false, epoch, user, epoch, user, schema, groupR)
    val acl3: AclResource = ResourceF(Root, 3L, types, false, epoch, user, epoch, anon, schema, groupX)

    "be merged with other ACL collection" in {
      val acls1    = AclCollection(acl)
      val acls2    = AclCollection(acl2, acl3.copy(id = projAddress))
      val expected =
        AclCollection(acl2.as(Acl(user -> Set(r, w), group -> Set(r, x))), acl3.copy(id = projAddress))

      acls1 ++ acls2 shouldEqual expected

      acls1 + acl2 + acl3.copy(id = projAddress) shouldEqual expected

    }

    "filter identities" in {
      val acls = AclCollection(acl.copy(id = projAddress), acl2.copy(id = orgAddress), acl3)
      acls.filter(Set(user)) shouldEqual
        AclCollection(
          acl3.as(Acl.empty),
          acl2.copy(id = orgAddress, value = Acl.empty),
          acl.copy(id = projAddress, value = Acl(user -> Set(r, w)))
        )
      acls.filter(Set(user, group)) shouldEqual acls
    }

    "subtract an ACL" in {
      (AclCollection(acl) - acl3) shouldEqual AclCollection(acl3.copy(value = userRW))
      (AclCollection(acl) - acl) shouldEqual AclCollection.empty
    }

    "subtract an address" in {
      val acls = AclCollection(acl2, acl3.copy(id = projAddress))
      (acls - projAddress) shouldEqual AclCollection(acl2)
    }

    "remove empty ACL" in {
      val proj2 = Label.unsafe("proj2")
      val acls  = AclCollection(
        acl.copy(id = projAddress),
        acl.as(Acl.empty),
        acl.copy(id = Project(org, proj2), value = Acl(user -> Set(r, w), group -> Set.empty)),
        acl.copy(id = orgAddress, value = Acl(user -> Set.empty, group -> Set.empty))
      )
      acls.removeEmpty() shouldEqual
        AclCollection(
          acl.copy(id = projAddress),
          acl.copy(id = Project(org, proj2), value = Acl(user -> Set(r, w)))
        )
    }

    "check for matching identities and permission on a Root address" in {
      val acls = AclCollection(acl)

      forAll(List(projAddress, orgAddress, Root)) { address =>
        acls.exists(Set(user, anon), r, address) shouldEqual true
        acls.exists(Set(anon), r, address) shouldEqual false
        acls.exists(Set(user, anon), x, address) shouldEqual false
      }
    }

    "check for matching identities and permission on a Project address" in {
      val acls  = AclCollection(acl.copy(id = projAddress): AclResource)
      val proj2 = Label.unsafe("proj2")

      forAll(List(Project(org, proj2), orgAddress, Root)) { address =>
        acls.exists(Set(user, anon), r, address) shouldEqual false
      }
      acls.exists(Set(user, anon), r, projAddress) shouldEqual true
      acls.exists(Set(anon), r, projAddress) shouldEqual false
      acls.exists(Set(user, anon), x, projAddress) shouldEqual false
    }

    "fetch ACLs from given filter" in {
      val org2          = Label.unsafe("org2")
      val proj2         = Label.unsafe("proj2")
      val org2Address   = Organization(org2)
      val proj12Address = Project(org, proj2)
      val proj22Address = Project(org2, proj2)
      val any           = AnyOrganizationAnyProject
      val anyOrg        = AnyOrganization

      val orgAcl: AclResource    = acl2.copy(id = orgAddress)
      val org2Acl: AclResource   = acl3.copy(id = org2Address)
      val projAcl: AclResource   = acl2.copy(id = projAddress)
      val proj12Acl: AclResource = acl3.copy(id = proj12Address)
      val proj22Acl: AclResource = acl.copy(id = proj22Address)
      val acls                   = AclCollection(acl, orgAcl, org2Acl, projAcl, proj12Acl, proj22Acl)

      acls.fetch(any) shouldEqual AclCollection(projAcl, proj12Acl, proj22Acl)
      acls.fetch(AnyProject(org)) shouldEqual AclCollection(projAcl, proj12Acl)
      acls.fetch(anyOrg) shouldEqual AclCollection(orgAcl, org2Acl)
    }

    "fetch ACLs from given filter including the ancestor addresses" in {
      val org2          = Label.unsafe("org2")
      val proj2         = Label.unsafe("proj2")
      val org2Address   = Organization(org2)
      val proj12Address = Project(org, proj2)
      val proj22Address = Project(org2, proj2)
      val any           = AnyOrganizationAnyProject
      val anyOrg        = AnyOrganization

      val orgAcl: AclResource    = acl2.copy(id = orgAddress)
      val org2Acl: AclResource   = acl3.copy(id = org2Address)
      val projAcl: AclResource   = acl2.copy(id = projAddress)
      val proj12Acl: AclResource = acl3.copy(id = proj12Address)
      val proj22Acl: AclResource = acl.copy(id = proj22Address)
      val acls                   = AclCollection(acl, orgAcl, org2Acl, projAcl, proj12Acl, proj22Acl)

      acls.fetchWithAncestors(any) shouldEqual acls
      acls.fetchWithAncestors(AnyProject(org)) shouldEqual AclCollection(acl, orgAcl, projAcl, proj12Acl)
      acls.fetchWithAncestors(anyOrg) shouldEqual AclCollection(acl, orgAcl, org2Acl)
    }
  }

}
