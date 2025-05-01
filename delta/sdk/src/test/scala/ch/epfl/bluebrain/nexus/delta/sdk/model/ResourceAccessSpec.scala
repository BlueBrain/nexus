package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceAccess.{EphemeralAccess, InProjectAccess}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

class ResourceAccessSpec extends BaseSpec {

  implicit private val baseUri: BaseUri = BaseUri.unsafe("http://localhost", "v1")

  "ResourceAccess" should {
    val projectRef = ProjectRef.unsafe("myorg", "myproject")

    "be constructed for permissions" in {
      val expected = uri"http://localhost/v1/permissions"
      ResourceAccess.permissions.uri shouldEqual expected
    }

    "be constructed for acls" in {
      val org  = Label.unsafe("org")
      val proj = Label.unsafe("project")
      val list =
        List(
          ResourceAccess.acl(AclAddress.Root)               -> uri"http://localhost/v1/acls",
          ResourceAccess.acl(org)                           -> uri"http://localhost/v1/acls/org",
          ResourceAccess.acl(AclAddress.Project(org, proj)) -> uri"http://localhost/v1/acls/org/project"
        )
      forAll(list) { case (resourceUris, expected) =>
        resourceUris.uri shouldEqual expected
      }
    }

    "be constructed for realms" in {
      val myrealm      = Label.unsafe("myrealm")
      val expected     = uri"http://localhost/v1/realms/myrealm"
      val resourceUris = ResourceAccess.realm(myrealm)
      resourceUris.uri shouldEqual expected
    }

    "be constructed for organizations" in {
      val expected     = uri"http://localhost/v1/orgs/myorg"
      val resourceUris = ResourceAccess.organization(projectRef.organization)
      resourceUris.uri shouldEqual expected
    }

    "be constructed for projects" in {
      val expected     = uri"http://localhost/v1/projects/myorg/myproject"
      val resourceUris = ResourceAccess.project(projectRef)
      resourceUris.uri shouldEqual expected
    }

    "be constructed for schemas" in {
      val id       = schemas + "myid"
      val expected = uri"http://localhost/v1/schemas/myorg/myproject/" / id.toString

      val resourceUris = ResourceAccess.schema(projectRef, id).asInstanceOf[InProjectAccess]

      resourceUris.uri shouldEqual expected
      resourceUris.project shouldEqual projectRef
    }

    "be constructed for resolvers" in {
      val id       = nxv + "myid"
      val expected = uri"http://localhost/v1/resolvers/myorg/myproject" / id.toString

      val resourceUris = ResourceAccess.resolver(projectRef, id).asInstanceOf[InProjectAccess]
      resourceUris.uri shouldEqual expected
      resourceUris.project shouldEqual projectRef
    }

    "be constructed for ephemeral resources" in {
      val id       = nxv + "myid"
      val expected = uri"http://localhost/v1/archives/myorg/myproject" / id.toString

      val resourceUris = ResourceAccess.ephemeral("archives", projectRef, id)

      resourceUris match {
        case v: EphemeralAccess =>
          v.uri shouldEqual expected
          v.project shouldEqual projectRef
        case other              =>
          fail(
            s"Expected type 'EphemeralResourceInProjectUris', but got value '$other' of type '${other.getClass.getCanonicalName}'"
          )
      }
    }
  }
}
