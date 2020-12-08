package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceUrisSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable with OptionValues {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val mapping                   = ApiMappings(Map("nxv" -> nxv.base, "resolvers" -> schemas.resolvers))
  private val base                      = ProjectBase.unsafe(schemas.base)

  "ResourceUris" should {
    val projectRef = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject"))

    "be constructed for permissions" in {
      val expected = Uri("http://localhost/v1/permissions")
      ResourceUris.permissions.accessUri shouldEqual expected
      ResourceUris.permissions.accessUriShortForm shouldEqual expected
      ResourceUris.permissions.incoming shouldEqual None
      ResourceUris.permissions.outgoing shouldEqual None
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
        resourceUris.incoming shouldEqual None
        resourceUris.outgoing shouldEqual None
      }
    }

    "be constructed for realms" in {
      val myrealm      = Label.unsafe("myrealm")
      val expected     = Uri("http://localhost/v1/realms/myrealm")
      val resourceUris = ResourceUris.realm(myrealm)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
      resourceUris.incoming shouldEqual None
      resourceUris.outgoing shouldEqual None
    }

    "be constructed for organizations" in {
      val expected     = Uri("http://localhost/v1/orgs/myorg")
      val resourceUris = ResourceUris.organization(projectRef.organization)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
      resourceUris.incoming shouldEqual None
    }

    "be constructed for projects" in {
      val expected     = Uri("http://localhost/v1/projects/myorg/myproject")
      val resourceUris = ResourceUris.project(projectRef)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expected
      resourceUris.incoming shouldEqual None
      resourceUris.outgoing shouldEqual None
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

        val resourceUris = ResourceUris.resource(projectRef, id, Latest(schema))(mapping, base)
        resourceUris.accessUri shouldEqual expected
        resourceUris.accessUriShortForm shouldEqual expectedShort
        resourceUris.incoming.value shouldEqual expectedIn
        resourceUris.outgoing.value shouldEqual expectedOut
        resourceUris.incomingShortForm.value shouldEqual expectedInShort
        resourceUris.outgoingShortForm.value shouldEqual expectedOutShort
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

      val resourceUris = ResourceUris.schema(projectRef, id)(mapping, base)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.incoming.value shouldEqual expectedIn
      resourceUris.outgoing.value shouldEqual expectedOut
      resourceUris.incomingShortForm.value shouldEqual expectedInShort
      resourceUris.outgoingShortForm.value shouldEqual expectedOutShort
    }

    "be constructed for resolvers" in {
      val id               = nxv + "myid"
      val expected         = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedIn       = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/incoming")
      val expectedOut      = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}/outgoing")
      val expectedShort    = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid")
      val expectedInShort  = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid/incoming")
      val expectedOutShort = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid/outgoing")

      val resourceUris = ResourceUris.resolver(projectRef, id)(mapping, base)
      resourceUris.accessUri shouldEqual expected
      resourceUris.accessUriShortForm shouldEqual expectedShort
      resourceUris.incoming.value shouldEqual expectedIn
      resourceUris.outgoing.value shouldEqual expectedOut
      resourceUris.incomingShortForm.value shouldEqual expectedInShort
      resourceUris.outgoingShortForm.value shouldEqual expectedOutShort
    }

  }

}
