package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.{ResourceInProjectAndSchemaUris, ResourceInProjectUris}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceUrisSpec extends AnyWordSpecLike with Matchers with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val mapping                   = ApiMappings(Map("nxv" -> nxv.base, "resolvers" -> schemas.resolvers))
  private val base                      = ProjectBase.unsafe(schemas.base)

  "ResourceUris" should {
    val projectRef       = ProjectRef.unsafe("myorg", "myproject")
    val projectRefSchema = ProjectRef.unsafe("myorg", "myproject2")

    "be constructed for permissions" in {
      val expected = Uri("http://localhost/v1/permissions")
      ResourceUris.permissions.accessUri shouldEqual expected
      ResourceUris.permissions.accessUriShortForm shouldEqual expected
    }

    "be constructed for acls" in {
      val org  = Label.unsafe("org")
      val proj = Label.unsafe("project")
      val list =
        List(
          ResourceUris.acl(AclAddress.Root)               -> Uri("http://localhost/v1/acls"),
          ResourceUris.acl(AclAddress.Organization(org))  -> Uri("http://localhost/v1/acls/org"),
          ResourceUris.acl(AclAddress.Project(org, proj)) -> Uri("http://localhost/v1/acls/org/project")
        )
      forAll(list) { case (resourceUris, expected) =>
        resourceUris.accessUri shouldEqual expected
        resourceUris.accessUriShortForm shouldEqual expected
      }
    }

    "be constructed for realms" in {
      val myrealm      = Label.unsafe("myrealm")
      val expected     = Uri("http://localhost/v1/realms/myrealm")
      val resourceUris = ResourceUris.realm(myrealm)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
    }

    "be constructed for organizations" in {
      val expected     = Uri("http://localhost/v1/orgs/myorg")
      val resourceUris = ResourceUris.organization(projectRef.organization)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
    }

    "be constructed for projects" in {
      val expected     = Uri("http://localhost/v1/projects/myorg/myproject")
      val resourceUris = ResourceUris.project(projectRef)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
    }

    "be constructed for resources" in {
      val id        = nxv + "myid"
      val encodedId = UrlUtils.encode(id.toString)
      forAll(List(schemas.resources -> "_", schemas.resolvers -> "resolver")) { case (schema, shortForm) =>
        val encodedSchema    = UrlUtils.encode(schema.toString)
        val expected         = Uri(s"http://localhost/v1/resources/myorg/myproject/$encodedSchema/$encodedId")
        val expectedIn       = Uri(s"http://localhost/v1/resources/myorg/myproject/$encodedSchema/$encodedId/incoming")
        val expectedOut      = Uri(s"http://localhost/v1/resources/myorg/myproject/$encodedSchema/$encodedId/outgoing")
        val expectedShort    = Uri(s"http://localhost/v1/resources/myorg/myproject/$shortForm/nxv:myid")
        val expectedInShort  = Uri(s"http://localhost/v1/resources/myorg/myproject/$shortForm/nxv:myid/incoming")
        val expectedOutShort = Uri(s"http://localhost/v1/resources/myorg/myproject/$shortForm/nxv:myid/outgoing")

        val resourceUris = ResourceUris
          .resource(projectRef, projectRefSchema, id, Latest(schema))(mapping, base)
          .asInstanceOf[ResourceInProjectAndSchemaUris]

        resourceUris.accessUri shouldEqual expected
        resourceUris.accessUriShortForm shouldEqual expectedShort
        resourceUris.incoming shouldEqual expectedIn
        resourceUris.outgoing shouldEqual expectedOut
        resourceUris.incomingShortForm shouldEqual expectedInShort
        resourceUris.outgoingShortForm shouldEqual expectedOutShort
        resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
        resourceUris.schemaProject shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject2")
      }
    }

    "be constructed for schemas" in {
      val id               = schemas + "myid"
      val expected         = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn       = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut      = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")
      val expectedShort    = Uri("http://localhost/v1/schemas/myorg/myproject/myid")
      val expectedInShort  = Uri("http://localhost/v1/schemas/myorg/myproject/myid/incoming")
      val expectedOutShort = Uri("http://localhost/v1/schemas/myorg/myproject/myid/outgoing")

      val resourceUris = ResourceUris.schema(projectRef, id)(mapping, base).asInstanceOf[ResourceInProjectUris]

      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.incomingShortForm shouldEqual expectedInShort
      resourceUris.outgoingShortForm shouldEqual expectedOutShort
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
    }

    "be constructed for resolvers" in {
      val id               = nxv + "myid"
      val expected         = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn       = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut      = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")
      val expectedShort    = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid")
      val expectedInShort  = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid/incoming")
      val expectedOutShort = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid/outgoing")

      val resourceUris = ResourceUris.resolver(projectRef, id)(mapping, base).asInstanceOf[ResourceInProjectUris]
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.incomingShortForm shouldEqual expectedInShort
      resourceUris.outgoingShortForm shouldEqual expectedOutShort
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")
    }

    "be constructed for resolvers with nxv as a base" in {
      val id               = nxv + "myid"
      val expected         = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn       = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut      = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")
      val expectedShort    = Uri("http://localhost/v1/resolvers/myorg/myproject/myid")
      val expectedInShort  = Uri("http://localhost/v1/resolvers/myorg/myproject/myid/incoming")
      val expectedOutShort = Uri("http://localhost/v1/resolvers/myorg/myproject/myid/outgoing")

      val resourceUris =
        ResourceUris.resolver(projectRef, id)(mapping, ProjectBase.unsafe(nxv.base)).asInstanceOf[ResourceInProjectUris]
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.incoming shouldEqual expectedIn
      resourceUris.outgoing shouldEqual expectedOut
      resourceUris.incomingShortForm shouldEqual expectedInShort
      resourceUris.outgoingShortForm shouldEqual expectedOutShort
      resourceUris.project shouldEqual Uri(s"http://localhost/v1/projects/myorg/myproject")

    }
  }
}
