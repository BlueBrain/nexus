package ch.epfl.bluebrain.nexus.tests.admin

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.Identity.{Authenticated, UserCredentials}
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.ProjectsTag
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, ExpectedResponse, Identity, Realm}
import io.circe.Json
import monix.execution.Scheduler.Implicits.global

class ProjectsSpec extends BaseSpec {

  private val testRealm       = Realm("projects" + genString())
  private val testClient      = Identity.ClientCredentials(genString(), genString(), testRealm)
  private val Bojack          = UserCredentials(genString(), genString(), testRealm)
  private val PrincessCarolyn = UserCredentials(genString(), genString(), testRealm)

  import ch.epfl.bluebrain.nexus.tests.iam.types.Permission._

  private val UnauthorizedAccess = ExpectedResponse(
    StatusCodes.Forbidden,
    jsonContentOf("/iam/errors/unauthorized-access.json")
  )

  private val MethodNotAllowed = ExpectedResponse(
    StatusCodes.MethodNotAllowed,
    jsonContentOf("/admin/errors/method-not-supported.json")
  )

  private val ProjectConflict = ExpectedResponse(
    StatusCodes.Conflict,
    jsonContentOf("/admin/errors/project-incorrect-revision.json")
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    initRealm(
      testRealm,
      Identity.ServiceAccount,
      testClient,
      Bojack :: PrincessCarolyn :: Nil
    ).runSyncUnsafe()
  }

  "projects API" should {

    val orgId  = genId()
    val projId = genId()
    val id     = s"$orgId/$projId"

    "fail to create project if the permissions are missing" taggedAs ProjectsTag in {
      adminDsl.createProject(
        orgId,
        projId,
        Json.obj(),
        Bojack,
        Some(UnauthorizedAccess)
      )
    }

    "add organizations/create permissions for user" taggedAs ProjectsTag in {
      aclDsl.addPermissions(
        "/",
        Bojack,
        Set(Organizations.Create)
      )
    }

    "fail to create if the HTTP verb used is POST" taggedAs ProjectsTag in {
      deltaClient.post[Json](s"/projects/$id", Json.obj(), Bojack) { (json, response) =>
        response.status shouldEqual MethodNotAllowed.statusCode
        json shouldEqual MethodNotAllowed.json
      }
    }

    "create organization" taggedAs ProjectsTag in {
      adminDsl.createOrganization(
        orgId,
        "Description",
        Bojack
      )
    }

    val description = s"$id project"
    val base        = s"${config.deltaUri.toString()}/resources/$id/_/"
    val vocab       = s"${config.deltaUri.toString()}/vocabs/$id/"

    val createJson = adminDsl.projectPayload(
      nxv = "nxv",
      person = "person",
      description = description,
      base = base,
      vocab = vocab
    )

    "return not found when fetching a non existing project" taggedAs ProjectsTag in {
      deltaClient.get[Json](s"/projects/$orgId/${genId()}", Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "Clean permissions and add projects/create permissions" taggedAs ProjectsTag in {
      for {
        _ <- aclDsl.cleanAcls(Bojack)
        _ <- aclDsl.addPermissions(
               s"/$orgId",
               Bojack,
               Set(Projects.Create)
             )
      } yield succeed
    }

    "create project" taggedAs ProjectsTag in {
      adminDsl.createProject(
        orgId,
        projId,
        createJson,
        Bojack
      )
    }

    "fail to create if project already exists" taggedAs ProjectsTag in {
      val conflict = ExpectedResponse(
        StatusCodes.Conflict,
        jsonContentOf(
          "/admin/errors/project-already-exists.json",
          "projLabel" -> projId,
          "orgId"     -> orgId,
          "projId"    -> id
        )
      )

      adminDsl.createProject(
        orgId,
        projId,
        createJson,
        Bojack,
        Some(conflict)
      )
    }

    "ensure that necessary permissions have been set" taggedAs ProjectsTag in {
      aclDsl.checkAdminAcls(s"/$id", Bojack)
    }

    "fetch the project" taggedAs ProjectsTag in {
      deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        admin.validateProject(json, createJson)
        admin.validate(json, "Project", "projects", id, description, 1L, projId)
      }
    }

    "fetch project by UUID" taggedAs ProjectsTag in {
      deltaClient.get[Json](s"/orgs/$orgId", Identity.ServiceAccount) { (orgJson, _) =>
        runTask {
          val orgUuid = _uuid.getOption(orgJson).value
          deltaClient.get[Json](s"/projects/$id", Bojack) { (projectJson, _) =>
            runTask {
              val projectUuid = _uuid.getOption(projectJson).value
              deltaClient.get[Json](s"/projects/$orgUuid/$projectUuid", Bojack) { (json, response) =>
                response.status shouldEqual StatusCodes.OK
                json shouldEqual projectJson
              }
            }
          }
        }
      }
    }

    "return not found when fetching a non existing revision of a project" taggedAs ProjectsTag in {
      deltaClient.get[Json](s"/projects/$id?rev=3", Bojack) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "update project and fetch revisions" taggedAs ProjectsTag in {
      val descRev2       = s"$description update 1"
      val baseRev2       = s"${config.deltaUri.toString()}/${genString()}/"
      val vocabRev2      = s"${config.deltaUri.toString()}/${genString()}/"
      val updateRev2Json = adminDsl.projectPayload(
        "/admin/projects/update.json",
        "nxv",
        "person",
        description = descRev2,
        base = baseRev2,
        vocab = vocabRev2
      )

      val descRev3       = s"$description update 2"
      val baseRev3       = s"${config.deltaUri.toString()}/${genString()}/"
      val vocabRev3      = s"${config.deltaUri.toString()}/${genString()}/"
      val updateRev3Json = adminDsl.projectPayload(
        "/admin/projects/update.json",
        "nxv",
        "person",
        description = descRev3,
        base = baseRev3,
        vocab = vocabRev3
      )

      for {
        _ <- adminDsl.updateProject(
               orgId,
               projId,
               updateRev2Json,
               Bojack,
               1L
             )
        _ <- adminDsl.updateProject(
               orgId,
               projId,
               updateRev3Json,
               Bojack,
               2L
             )
        _ <- deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validateProject(json, updateRev3Json)
               admin.validate(json, "Project", "projects", id, descRev3, 3L, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=3", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validateProject(json, updateRev3Json)
               admin.validate(json, "Project", "projects", id, descRev3, 3L, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=2", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validateProject(json, updateRev2Json)
               admin.validate(json, "Project", "projects", id, descRev2, 2L, projId)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=1", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validateProject(json, createJson)
               admin.validate(json, "Project", "projects", id, description, 1L, projId)
             }
      } yield succeed
    }

    "reject update  when wrong revision is provided" taggedAs ProjectsTag in {
      deltaClient.put[Json](s"/projects/$id?rev=4", createJson, Bojack) { (json, response) =>
        response.status shouldEqual ProjectConflict.statusCode
        json shouldEqual ProjectConflict.json
      }
    }

    "deprecate project" taggedAs ProjectsTag in {
      for {
        _ <- deltaClient.delete[Json](s"/projects/$id?rev=3", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) shouldEqual adminDsl.createProjectRespJson(
                 projId,
                 orgId,
                 4L,
                 authenticated = Bojack,
                 schema = "projects",
                 deprecated = true
               )
             }
        _ <- deltaClient.get[Json](s"/projects/$id", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Project", "projects", id, s"$description update 2", 4L, projId, deprecated = true)
             }
        _ <- deltaClient.get[Json](s"/projects/$id?rev=1", Bojack) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Project", "projects", id, description, 1L, projId)
             }
      } yield succeed
    }
  }

  "listing projects" should {

    "return empty list if no acl is set" taggedAs ProjectsTag in {
      deltaClient.get[Json]("/projects", PrincessCarolyn) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf("/admin/projects/empty-project-list.json")
      }
    }

    "add projects/create permissions for user 2" taggedAs ProjectsTag in {
      aclDsl.addPermission(
        s"/${genId()}",
        PrincessCarolyn,
        Projects.Read
      )
    }

    "return an empty list if no project is accessible" taggedAs ProjectsTag in {
      deltaClient.get[Json]("/projects", PrincessCarolyn) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf("/admin/projects/empty-project-list.json")
      }
    }

    "add projects/create permissions for user" taggedAs ProjectsTag in {
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
            "/admin/projects/listing-item.json",
            replacements(
              target,
              "id"     -> s"$orgId/$projectId",
              "projId" -> projectId,
              "orgId"  -> orgId
            ): _*
          )
        }: _*
      )
    }

    "create projects" taggedAs ProjectsTag in {
      for {
        _ <- adminDsl.createOrganization(
               orgId,
               "Description",
               Bojack
             )
        _ <- projectIds.traverse { case (orgId, projId) =>
               adminDsl.createProject(
                 orgId,
                 projId,
                 adminDsl.projectPayload(
                   nxv = s"nxv-$projId",
                   person = s"person-$projId",
                   description = projId,
                   base = s"http://example.com/$projId/",
                   vocab = s"http://example.com/$projId/vocab/"
                 ),
                 Bojack
               )
             }
      } yield succeed
    }

    "list projects" taggedAs ProjectsTag in {
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
        filterResultMetadata(json) shouldEqual expectedResults
      }
    }

    "list projects which user has access to" taggedAs ProjectsTag in {
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
        _ <- projectsToList.traverse { case (orgId, projectId) =>
               aclDsl.addPermission(
                 s"/$orgId/$projectId",
                 PrincessCarolyn,
                 Projects.Read
               )
             }
        _ <- deltaClient.get[Json](s"/projects/$orgId", PrincessCarolyn) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterResultMetadata(json) shouldEqual expectedResults
             }
      } yield succeed
    }
  }

}
