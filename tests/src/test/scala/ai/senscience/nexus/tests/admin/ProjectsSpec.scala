package ai.senscience.nexus.tests.admin

import ai.senscience.nexus.tests.Identity.Authenticated
import ai.senscience.nexus.tests.Identity.projects.{Bojack, PrincessCarolyn}
import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.{BaseIntegrationSpec, OpticsValidators}
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ProjectMatchers.deprecated
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.scalactic.source.Position

class ProjectsSpec extends BaseIntegrationSpec with OpticsValidators {

  import ai.senscience.nexus.tests.iam.types.Permission.*

  "projects API" should {

    val orgId  = genId()
    val projId = genId()
    val id     = s"$orgId/$projId"

    val description = s"$id project"
    val base        = s"${config.deltaUri.toString()}/resources/$id/_/"
    val vocab       = Some(s"${config.deltaUri.toString()}/vocabs/$id/")

    val payload = ProjectPayload(
      description,
      base,
      vocab,
      Map(
        "nxv"    -> "https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/",
        "person" -> "https://shapes-registry.org/commons/person"
      ),
      enforceSchema = false
    )

    "fail to create project if the permissions are missing" in {
      adminDsl.createProject(orgId, projId, payload, Bojack, Some(StatusCodes.Forbidden))
    }

    "add organizations/create permissions for user" in {
      aclDsl.addPermissions(
        "/",
        Bojack,
        Set(Organizations.Create)
      )
    }

    "create organization" in {
      adminDsl.createOrganization(
        orgId,
        "Description",
        Bojack
      )
    }

    "return not found when fetching a non existing project" in {
      deltaClient.get[Json](s"/projects/$orgId/${genId()}", Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "Clean permissions and add projects/create permissions" in {
      for {
        _ <- aclDsl.cleanAcls(Bojack)
        _ <- aclDsl.addPermissions(
               s"/$orgId",
               Bojack,
               Set(Projects.Create)
             )
      } yield succeed
    }

    "fail to create if the HTTP verb used is POST" in {
      deltaClient.post[Json](s"/projects/$id", Json.obj(), Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "create project" in {
      adminDsl.createProject(orgId, projId, payload, Bojack)
    }

    "fail to create if project already exists" in {
      adminDsl.createProject(orgId, projId, payload, Bojack, Some(StatusCodes.Conflict))
    }

    "ensure that necessary permissions have been set" in {
      aclDsl.checkAdminAcls(s"/$id", Bojack)
    }

    "fetch the project" in {
      deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        validateProject(json, payload)
        validate(json, "Project", "projects", id, description, 1, projId)
      }
    }

    "return not found when fetching a non existing revision of a project" in {
      deltaClient.get[Json](s"/projects/$id?rev=3", Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "update project and fetch revisions" in {
      val descRev2      = s"$description update 1"
      val baseRev2      = s"${config.deltaUri.toString()}/${genString()}/"
      val vocabRev2     = Some(s"${config.deltaUri.toString()}/${genString()}/")
      val newApiMapping = "xsd" -> "http://www.w3.org/2001/XMLSchema#"
      val updateRev2    = payload.copy(
        description = descRev2,
        base = baseRev2,
        vocab = vocabRev2,
        apiMappings = payload.apiMappings + newApiMapping
      )

      val descRev3   = s"$description update 2"
      val baseRev3   = s"${config.deltaUri.toString()}/${genString()}/"
      val vocabRev3  = Some(s"${config.deltaUri.toString()}/${genString()}/")
      val updateRev3 = payload.copy(
        description = descRev3,
        base = baseRev3,
        vocab = vocabRev3,
        apiMappings = payload.apiMappings + newApiMapping,
        enforceSchema = true
      )

      for {
        _ <- adminDsl.updateProject(
               orgId,
               projId,
               updateRev2,
               Bojack,
               1
             )
        _ <- adminDsl.updateProject(
               orgId,
               projId,
               updateRev3,
               Bojack,
               2
             )
        _ <- deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validateProject(json, updateRev3)
               validate(json, "Project", "projects", id, descRev3, 3, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=3", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validateProject(json, updateRev3)
               validate(json, "Project", "projects", id, descRev3, 3, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=2", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validateProject(json, updateRev2)
               validate(json, "Project", "projects", id, descRev2, 2, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=1", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validateProject(json, payload)
               validate(json, "Project", "projects", id, description, 1, projId)
             }
      } yield succeed
    }

    "reject update  when wrong revision is provided" in {
      deltaClient.put[Json](s"/projects/$id?rev=4", payload.asJson, Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.Conflict
      }
    }

    "deprecate project" in {
      for {
        _ <- deltaClient.delete[Json](s"/projects/$id?rev=3", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterProjectMetadataKeys(json) shouldEqual adminDsl
                 .createProjectRespJson(
                   projId,
                   orgId,
                   4,
                   authenticated = Bojack,
                   schema = "projects",
                   deprecated = true
                 )
                 .accepted
             }
        _ <- deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validate(json, "Project", "projects", id, s"$description update 2", 4, projId, deprecated = true)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=1", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               validate(json, "Project", "projects", id, description, 1, projId)
             }
      } yield succeed
    }

    "undeprecate project" in {
      for {
        _       <- undeprecateProject(orgId, projId, 4)
        project <- getProjectLatest(orgId, projId)
      } yield {
        project shouldNot be(deprecated)
      }
    }

    "get a redirect to fusion if a `text/html` header is provided" in
      deltaClient.get[String](
        s"/projects/$id",
        Rick,
        extraHeaders = List(Accept(MediaRange.One(`text/html`, 1f)))
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.SeeOther
        response
          .header[Location]
          .value
          .uri
          .toString() shouldEqual s"https://bbp.epfl.ch/nexus/web/admin/$id"
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
  }

  "listing projects" should {

    "return empty list if no acl is set" in {
      deltaClient.get[Json]("/projects", PrincessCarolyn) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf("admin/projects/empty-project-list.json")
      }
    }

    "add projects/create permissions for user 2" in {
      aclDsl.addPermission(
        s"/${genId()}",
        PrincessCarolyn,
        Projects.Read
      )
    }

    "return an empty list if no project is accessible" in {
      deltaClient.get[Json]("/projects", PrincessCarolyn) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf("admin/projects/empty-project-list.json")
      }
    }

    "add projects/create permissions for user" in {
      aclDsl.addPermissions(
        "/",
        Bojack,
        Set(Organizations.Create, Projects.Read)
      )
    }

    val orgId = genId()

    val projectIds: List[(String, String)] =
      (1 to 5)
        .map { _ =>
          (orgId, genId())
        }
        .sorted
        .toList

    def projectListingResults(ids: Seq[(String, String)], target: Authenticated): Json = {
      Json.arr(
        ids.map { case (orgId, projectId) =>
          jsonContentOf(
            "admin/projects/listing-item.json",
            replacements(
              target,
              "id"     -> s"$orgId/$projectId",
              "projId" -> projectId,
              "orgId"  -> orgId
            )*
          )
        }*
      )
    }

    "create projects" in {
      for {
        _ <- adminDsl.createOrganization(orgId, "Description", Bojack)
        _ <- projectIds.traverse { case (orgId, projId) =>
               val payload = ProjectPayload(
                 description = projId,
                 base = s"http://example.com/$projId/",
                 vocab = Some(s"http://example.com/$projId/vocab/"),
                 Map(
                   s"nxv-$projId"    -> "https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/",
                   s"person-$projId" -> "https://shapes-registry.org/commons/person"
                 ),
                 enforceSchema = false
               )
               adminDsl.createProject(orgId, projId, payload, Bojack)
             }
      } yield succeed
    }

    "list projects" in {
      val expectedResults = Json.obj(
        "@context" -> Json.arr(
          Json.fromString("https://bluebrain.github.io/nexus/contexts/metadata.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/projects.json")
        ),
        "_total"   -> Json.fromInt(projectIds.size),
        "_results" -> projectListingResults(projectIds, Bojack)
      )

      deltaClient.get[Json](s"/projects/$orgId", Bojack) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterResultMetadata(json) should equalIgnoreArrayOrder(expectedResults)
      }
    }

    "list projects which user has access to" in {
      val projectsToList  = projectIds.slice(0, 2)
      val expectedResults = Json.obj(
        "@context" -> Json.arr(
          Json.fromString("https://bluebrain.github.io/nexus/contexts/metadata.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/projects.json")
        ),
        "_total"   -> Json.fromInt(projectsToList.size),
        "_results" -> projectListingResults(projectsToList, Bojack)
      )

      for {
        _ <- projectsToList.parTraverse { case (orgId, projectId) =>
               aclDsl.addPermission(
                 s"/$orgId/$projectId",
                 PrincessCarolyn,
                 Projects.Read
               )
             }
        _ <- deltaClient.get[Json](s"/projects/$orgId", PrincessCarolyn) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterResultMetadata(json) should equalIgnoreArrayOrder(expectedResults)
             }
      } yield succeed
    }
  }

  def undeprecateProject(org: String, project: String, revision: Int)(implicit pos: Position) = {
    deltaClient.put[Json](s"/projects/$org/$project/undeprecate?rev=$revision", Json.obj(), Bojack) { (_, response) =>
      response.status shouldBe StatusCodes.OK
    }
  }

  def getProjectLatest(org: String, project: String): IO[Json] = {
    deltaClient.getJson[Json](s"/projects/$org/$project", Bojack)
  }
}
