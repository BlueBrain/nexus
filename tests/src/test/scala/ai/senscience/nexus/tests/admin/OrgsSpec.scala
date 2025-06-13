package ai.senscience.nexus.tests.admin

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.orgs.{Deleter, Reader, Writer}
import ai.senscience.nexus.tests.{BaseIntegrationSpec, OpticsValidators}
import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.scalatest.OrgMatchers.deprecated
import io.circe.Json

class OrgsSpec extends BaseIntegrationSpec with OpticsValidators {

  import ai.senscience.nexus.tests.iam.types.Permission.*

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- aclDsl.addPermission("/", Writer, Organizations.Create)
      _ <- aclDsl.addPermission("/", Writer, Organizations.Write)
      _ <- aclDsl.addPermission("/", Reader, Organizations.Read)
      _ <- aclDsl.addPermission("/", Deleter, Organizations.Delete)
    } yield ()
    setup.accepted
  }

  "creating an organization" should {
    "fail if the permissions are missing" in {
      adminDsl.createOrganization(genId(), "Description", Reader, Some(StatusCodes.Forbidden))
    }

    "succeed if payload is correct and permissions should be set" in {
      val id = genId()
      for {
        _ <- adminDsl.createOrganization(id, "Description", Writer)
        _ <- aclDsl.checkAdminAcls(s"/$id", Writer)
      } yield succeed
    }

    "fail if organization already exists" in {
      val duplicate = genId()

      for {
        _ <- adminDsl.createOrganization(duplicate, "Description", Writer)
        _ <- adminDsl.createOrganization(duplicate, "Description", Writer, Some(StatusCodes.Conflict))
      } yield succeed
    }
  }

  "fetching an organization" should {
    val id = genId()
    "fail if the permissions are missing" in {
      for {
        _ <- adminDsl.createOrganization(id, s"Description $id", Writer)
        _ <- deltaClient.get[Json](s"/orgs/$id", Anonymous) { expectForbidden }
      } yield succeed
    }

    "succeed if organization exists" in {
      deltaClient.get[Json](s"/orgs/$id", Reader) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        validate(json, "Organization", "orgs", id, s"Description $id", 1, id)
      }
    }

    "return not found when fetching a non existing revision of an organizations" in {
      deltaClient.get[Json](s"/orgs/$id?rev=3", Reader) { expectNotFound }
    }

    val nonExistent = genId()
    "add orgs/read permissions for non-existing organization" in {
      aclDsl.addPermission(s"/$nonExistent", Reader, Organizations.Create)
    }

    "return not found when fetching a non existing organization" in {
      deltaClient.get[Json](s"/orgs/$nonExistent", Reader) { expectNotFound }
    }
  }

  "updating an organization" should {
    val id          = genString()
    val description = s"$id organization"

    "fail if the permissions are missing" in {
      adminDsl.createOrganization(id, description, Reader, Some(StatusCodes.Forbidden))
    }

    "create organization" in {
      adminDsl.createOrganization(id, description, Writer)
    }

    "fail when wrong revision is provided" in {
      adminDsl.updateOrganization(id, description, Writer, 4, Some(StatusCodes.Conflict))
    }

    val nonExistent = genId()

    "fail when organization does not exist" in {
      adminDsl.updateOrganization(nonExistent, description, Writer, 1, Some(StatusCodes.NotFound))
    }

    "succeed and fetch revisions" in {
      val updatedName  = s"$id organization update 1"
      val updatedName2 = s"$id organization update 2"

      for {
        _           <- adminDsl.updateOrganization(id, updatedName, Writer, 1)
        _           <- adminDsl.updateOrganization(id, updatedName2, Writer, 2)
        lastVersion <- deltaClient.getJson[Json](s"/orgs/$id", Reader)
        _            = validate(lastVersion, "Organization", "orgs", id, updatedName2, 3, id)
        _           <- deltaClient.get[Json](s"/orgs/$id?rev=3", Reader) { (thirdVersion, response) =>
                         response.status shouldEqual StatusCodes.OK
                         thirdVersion shouldEqual lastVersion
                       }
        _           <- deltaClient.get[Json](s"/orgs/$id?rev=2", Reader) { (json, response) =>
                         response.status shouldEqual StatusCodes.OK
                         validate(json, "Organization", "orgs", id, updatedName, 2, id)
                       }
        _           <- deltaClient.get[Json](s"/orgs/$id?rev=1", Reader) { (json, response) =>
                         response.status shouldEqual StatusCodes.OK
                         validate(json, "Organization", "orgs", id, s"$id organization", 1, id)
                       }
      } yield succeed
    }
  }

  "deprecating an organization" should {
    val id   = genId()
    val name = genString()

    "create the organization" in {
      adminDsl.createOrganization(id, name, Writer)
    }

    "fail if the permissions are missing" in {
      deltaClient.delete[Json](s"/orgs/$id?rev=2", Reader) { expectForbidden }
    }

    "fail when wrong revision is provided" in {
      deltaClient.delete[Json](s"/orgs/$id?rev=4", Writer) { (json, response) =>
        response.status shouldEqual StatusCodes.Conflict
        val expected = json"""{
                                "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
                                "@type": "IncorrectRev",
                                "expected": 1,
                                "provided": 4,
                                "reason": "Incorrect revision '4' provided, expected '1', the organization may have been updated since last seen."
                              }"""
        json shouldEqual expected
      }
    }

    "fail when revision is not provided" in {
      deltaClient.delete[Json](s"/orgs/$id", Writer) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json should have(`@type`("InvalidDeleteRequest"))
      }
    }

    "succeed if organization exists" in {
      for {
        _ <- adminDsl.deprecateOrganization(id, Writer)
        _ <- deltaClient.get[Json](s"/orgs/$id", Reader) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validate(json, "Organization", "orgs", id, name, 2, id, deprecated = true)
             }
        _ <- deltaClient.get[Json](s"/orgs/$id?rev=1", Reader) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validate(json, "Organization", "orgs", id, name, 1, id)
             }
      } yield succeed
    }
  }

  "undeprecate an org" in {
    val org = genId()
    for {
      _   <- adminDsl.createOrganization(org, genString(), Writer)
      _   <- adminDsl.deprecateOrganization(org, Writer)
      // Reader's attempt should be rejected
      _   <- deltaClient.put[Json](s"/orgs/$org/undeprecate?rev=2", Json.obj(), Reader) { expectForbidden }
      // Writer's attempt should be successful
      _   <- deltaClient.put[Json](s"/orgs/$org/undeprecate?rev=2", Json.obj(), Writer) { expectOk }
      org <- deltaClient.getJson[Json](s"/orgs/$org", Reader)
    } yield {
      org shouldNot be(deprecated)
    }
  }

  "deleting an org" should {

    "be successful" in {
      val id   = genId()
      val name = genString()
      for {
        _ <- adminDsl.createOrganization(id, name, Writer)
        // Reader's attempt should be rejected
        _ <- deltaClient.delete[Json](s"/orgs/$id?prune=true", Reader) { expectForbidden }
        // Deleter's attempt should be successful
        _ <- deltaClient.delete[Json](s"/orgs/$id?prune=true", Deleter) { expectOk }
        _ <- deltaClient.get[Json](s"/orgs/$id", Reader) { expectNotFound }
      } yield succeed
    }

    "fail when the org contains a project" in {
      val id   = genId()
      val name = genString()
      for {
        _ <- adminDsl.createOrganization(id, name, Writer)
        _ <- adminDsl.createProjectWithName(id, genId(), genString(), Writer)
        _ <- deltaClient.delete[Json](s"/orgs/$id?prune=true", Deleter) { (json, response) =>
               response.status shouldEqual StatusCodes.BadRequest
               json should have(`@type`("OrganizationNonEmpty"))
             }
        _ <- deltaClient.get[Json](s"/orgs/$id", Reader) { expectOk }
      } yield succeed
    }
  }
}
