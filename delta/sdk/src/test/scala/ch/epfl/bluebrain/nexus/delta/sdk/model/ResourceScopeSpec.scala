package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceScope.{EphemeralResourceF, ScopedResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

class ResourceScopeSpec extends BaseSpec {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  "ResourceUris" should {
    val projectRef = ProjectRef.unsafe("myorg", "myproject")

    "be constructed for permissions" in {
      val expected = Uri("http://localhost/v1/permissions")
      ResourceScope.permissions.accessUri shouldEqual expected
    }

    "be constructed for acls" in {
      val org  = Label.unsafe("org")
      val proj = Label.unsafe("project")
      val list =
        List(
          ResourceScope.acl(AclAddress.Root)               -> Uri("http://localhost/v1/acls"),
          ResourceScope.acl(org)                           -> Uri("http://localhost/v1/acls/org"),
          ResourceScope.acl(AclAddress.Project(org, proj)) -> Uri("http://localhost/v1/acls/org/project")
        )
      forAll(list) { case (resourceUris, expected) =>
        resourceUris.accessUri shouldEqual expected
      }
    }

    "be constructed for realms" in {
      val myrealm      = Label.unsafe("myrealm")
      val expected     = Uri("http://localhost/v1/realms/myrealm")
      val resourceUris = ResourceScope.realm(myrealm)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for organizations" in {
      val expected     = Uri("http://localhost/v1/orgs/myorg")
      val resourceUris = ResourceScope.organization(projectRef.organization)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for projects" in {
      val expected     = Uri("http://localhost/v1/projects/myorg/myproject")
      val resourceUris = ResourceScope.project(projectRef)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for schemas" in {
      val id       = schemas + "myid"
      val expected = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}")

      val resourceUris = ResourceScope.schema(projectRef, id).asInstanceOf[ScopedResourceF]

      resourceUris.accessUri shouldEqual expected
      resourceUris.project shouldEqual projectRef
    }

    "be constructed for resolvers" in {
      val id       = nxv + "myid"
      val expected = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")

      val resourceUris = ResourceScope.resolver(projectRef, id).asInstanceOf[ScopedResourceF]
      resourceUris.accessUri shouldEqual expected
      resourceUris.project shouldEqual projectRef
    }

    "be constructed for resolvers with nxv as a base" in {
      val id       = nxv + "myid"
      val expected = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")

      val resourceUris = ResourceScope.resolver(projectRef, id).asInstanceOf[ScopedResourceF]
      resourceUris.accessUri shouldEqual expected
      resourceUris.project shouldEqual projectRef
    }

    "be constructed for ephemeral resources" in {
      val segment      = genString()
      val id           = nxv + "myid"
      val expected     = Uri(s"http://localhost/v1/$segment/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val resourceUris = ResourceScope.ephemeral(segment, projectRef, id)

      resourceUris match {
        case v: EphemeralResourceF =>
          v.accessUri shouldEqual expected
          v.project shouldEqual projectRef
        case other                 =>
          fail(
            s"Expected type 'EphemeralResourceInProjectUris', but got value '$other' of type '${other.getClass.getCanonicalName}'"
          )
      }
    }
  }
}
