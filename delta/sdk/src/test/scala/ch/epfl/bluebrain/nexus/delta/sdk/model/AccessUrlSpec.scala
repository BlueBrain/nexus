package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AccessUrlSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {

  implicit private val base: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val mappings               = ApiMappings(Map("nxv" -> nxv.base, "resolvers" -> schemas.resolvers))

  "An AccessUrl" should {
    val projectRef = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject"))

    "be constructed for permissions" in {
      val expected  = Uri("http://localhost/v1/permissions")
      val accessUrl = AccessUrl.permissions
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expected
    }

    "be constructed for acls" in {
      val org     = Label.unsafe("org")
      val project = Label.unsafe("project")
      val list    =
        List(
          AccessUrl.acl(AclAddress.Root)                  -> Uri("http://localhost/v1/acls"),
          AccessUrl.acl(AclAddress.Organization(org))     -> Uri("http://localhost/v1/acls/org"),
          AccessUrl.acl(AclAddress.Project(org, project)) -> Uri("http://localhost/v1/acls/org/project")
        )
      forAll(list) { case (accessUrl, expected) =>
        accessUrl.value shouldEqual expected
        accessUrl.iri shouldEqual expected.toIri
        accessUrl.shortForm(mappings) shouldEqual expected
      }
    }

    "be constructed for realms" in {
      val myrealm   = Label.unsafe("myrealm")
      val expected  = Uri("http://localhost/v1/realms/myrealm")
      val accessUrl = AccessUrl.realm(myrealm)
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expected
    }

    "be constructed for organizations" in {
      val expected  = Uri("http://localhost/v1/orgs/myorg")
      val accessUrl = AccessUrl.organization(projectRef.organization)
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expected
    }

    "be constructed for projects" in {
      val expected  = Uri("http://localhost/v1/projects/myorg/myproject")
      val accessUrl = AccessUrl.project(projectRef)
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expected
    }

    "be constructed for resources" in {
      val id        = nxv + "myid"
      val encodedId = UrlUtils.encode(id.toString)
      forAll(List(schemas.resources -> "_", schemas.resolvers -> "resolver")) { case (schema, shortForm) =>
        val encodedSchema = UrlUtils.encode(schema.toString)
        val expected      = Uri(s"http://localhost/v1/resources/myorg/myproject/$encodedSchema/$encodedId")
        val expectedShort = Uri(s"http://localhost/v1/resources/myorg/myproject/$shortForm/nxv:myid")
        val accessUrl     = AccessUrl.resource(projectRef, id, Latest(schema))
        accessUrl.value shouldEqual expected
        accessUrl.iri shouldEqual expected.toIri
        accessUrl.shortForm(mappings) shouldEqual expectedShort
      }
    }

    "be constructed for schemas" in {
      val id            = nxv + "myid"
      val expected      = Uri(s"http://localhost/v1/schemas/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedShort = Uri("http://localhost/v1/schemas/myorg/myproject/nxv:myid")
      val accessUrl     = AccessUrl.schema(projectRef, id)
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expectedShort
    }

    "be constructed for resolvers" in {
      val id            = nxv + "myid"
      val expected      = Uri(s"http://localhost/v1/resolvers/myorg/myproject/${UrlUtils.encode(id.toString)}")
      val expectedShort = Uri("http://localhost/v1/resolvers/myorg/myproject/nxv:myid")
      val accessUrl     = AccessUrl.resolver(projectRef, id)
      accessUrl.value shouldEqual expected
      accessUrl.iri shouldEqual expected.toIri
      accessUrl.shortForm(mappings) shouldEqual expectedShort
    }

  }

}
