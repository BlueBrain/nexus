package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.{EphemeralResourceInProjectUris, ResourceInProjectAndSchemaUris, ResourceInProjectUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.genString
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceUrisSpec extends AnyWordSpecLike with Matchers with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  "ResourceUris" should {
    val projectRef       = ProjectRef.unsafe("myorg", "myproject")
    val projectRefSchema = ProjectRef.unsafe("myorg", "myproject2")

    "be constructed for permissions" in {
      val expected = Uri("http://localhost/v1/permissions")
      ResourceUris.permissions.accessUri shouldEqual expected
    }

    "be constructed for acls" in {
      val org  = Label.unsafe("org")
      val proj = Label.unsafe("project")
      val list =
        List(
          ResourceUris.acl(AclAddress.Root)               -> Uri("http://localhost/v1/acls"),
          ResourceUris.acl(org)                           -> Uri("http://localhost/v1/acls/org"),
          ResourceUris.acl(AclAddress.Project(org, proj)) -> Uri("http://localhost/v1/acls/org/project")
        )
      forAll(list) { case (resourceUris, expected) =>
        resourceUris.accessUri shouldEqual expected
      }
    }

    "be constructed for realms" in {
      val myrealm      = Label.unsafe("myrealm")
      val expected     = Uri("http://localhost/v1/realms/myrealm")
      val resourceUris = ResourceUris.realm(myrealm)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for organizations" in {
      val expected     = Uri("http://localhost/v1/orgs/myorg")
      val resourceUris = ResourceUris.organization(projectRef.organization)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for projects" in {
      val expected     = Uri("http://localhost/v1/projects/myorg/myproject")
      val resourceUris = ResourceUris.project(projectRef)
      resourceUris.accessUri shouldEqual expected
    }

    "be constructed for resources" in {
      val id          = nxv + "myid"
      val encodedId   = UrlUtils.encode(id.toString)
      val expected    = Uri(s"http://localhost/v1/resources/myorg/myproject/_/$encodedId")
      val expectedIn  = Uri(s"http://localhost/v1/resources/myorg/myproject/_/$encodedId/incoming")
      val expectedOut = Uri(s"http://localhost/v1/resources/myorg/myproject/_/$encodedId/outgoing")

      val resourceUris =
        ResourceUris.resource(projectRef, projectRefSchema, id).asInstanceOf[ResourceInProjectAndSchemaUris]

      resourceUris.accessUri shouldEqual expected
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
      resourceUris.schemaProject shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject2")
    }

    "be constructed for schemas" in {
      val id          = schemas + "myid"
      val expected    = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn  = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")

      val resourceUris = ResourceUris.schema(projectRef, id).asInstanceOf[ResourceInProjectUris]

      resourceUris.accessUri shouldEqual expected
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
    }

    "be constructed for resolvers" in {
      val id          = nxv + "myid"
      val expected    = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn  = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")

      val resourceUris = ResourceUris.resolver(projectRef, id).asInstanceOf[ResourceInProjectUris]
      resourceUris.accessUri shouldEqual expected
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
    }

    "be constructed for resolvers with nxv as a base" in {
      val id          = nxv + "myid"
      val expected    = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn  = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")

      val resourceUris =
        ResourceUris.resolver(projectRef, id).asInstanceOf[ResourceInProjectUris]
      resourceUris.accessUri shouldEqual expected
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
    }

    "be constructed for ephemeral resources" in {
      val segment         = genString()
      val id              = nxv + "myid"
      val expected        = Uri(s"http://localhost/v1/$segment/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedProject = Uri(s"http://localhost/v1/projects/myorg/myproject")
      val resourceUris    = ResourceUris.ephemeral(segment, projectRef, id)

      resourceUris match {
        case v: EphemeralResourceInProjectUris =>
          v.accessUri shouldEqual expected
          v.project shouldEqual expectedProject
        case other                             =>
          fail(
            s"Expected type 'EphemeralResourceInProjectUris', but got value '$other' of type '${other.getClass.getCanonicalName}'"
          )
      }
    }
  }
}
