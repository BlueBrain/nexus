package ch.epfl.bluebrain.nexus.tests.admin

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.tests.Identity.Authenticated
import ch.epfl.bluebrain.nexus.tests.Optics.{filterMetadataKeys, _}
import ch.epfl.bluebrain.nexus.tests.config.{PrefixesConfig, TestsConfig}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, ExpectedResponse, HttpClient, Identity}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

class AdminDsl(cl: HttpClient, prefixesConfig: PrefixesConfig, config: TestsConfig)
    extends TestHelpers
    with CirceUnmarshalling
    with Matchers {

  private val logger = Logger[this.type]

  def orgPayload(description: String = genString()): Json =
    jsonContentOf("/admin/orgs/payload.json", "description" -> description)

  def createOrgRespJson(
      id: String,
      rev: Long,
      tpe: String = "projects",
      `@type`: String = "Project",
      authenticated: Authenticated,
      schema: String,
      deprecated: Boolean = false
  ): Json = {
    val resp = Seq(prefixesConfig.coreContextRepl) ++ Seq(
      "id"         -> id,
      "path"       -> tpe,
      "type"       -> `@type`,
      "rev"        -> rev.toString,
      "deltaUri"   -> config.deltaUri.toString(),
      "realm"      -> authenticated.realm.name,
      "user"       -> authenticated.name,
      "orgId"      -> id,
      "deprecated" -> deprecated.toString,
      "schema"     -> schema
    )
    jsonContentOf("/admin/org-response.json", resp: _*)
  }

  def createProjectRespJson(
      id: String,
      orgId: String,
      rev: Long,
      tpe: String = "projects",
      `@type`: String = "Project",
      authenticated: Authenticated,
      schema: String,
      deprecated: Boolean = false
  ): Json = {
    val resp = Seq(prefixesConfig.coreContextRepl) ++ Seq(
      "projectId"  -> id,
      "path"       -> tpe,
      "type"       -> `@type`,
      "rev"        -> rev.toString,
      "deltaUri"   -> config.deltaUri.toString(),
      "realm"      -> authenticated.realm.name,
      "user"       -> authenticated.name,
      "orgId"      -> orgId,
      "deprecated" -> deprecated.toString,
      "schema"     -> schema
    )
    jsonContentOf("/admin/project-response.json", resp: _*)
  }

  private def queryParams(revision: Long) =
    if (revision == 0L) {
      ""
    } else {
      s"?rev=$revision"
    }

  def createOrganization(
      id: String,
      description: String,
      authenticated: Authenticated,
      expectedResponse: Option[ExpectedResponse] = None
  ): Task[Assertion] =
    updateOrganization(id, description, authenticated, 0L, expectedResponse)

  def updateOrganization(
      id: String,
      description: String,
      authenticated: Authenticated,
      revision: Long,
      expectedResponse: Option[ExpectedResponse] = None
  ): Task[Assertion] = {
    cl.put[Json](s"/orgs/$id${queryParams(revision)}", orgPayload(description), authenticated) { (json, response) =>
      expectedResponse match {
        case Some(e) =>
          response.status shouldEqual e.statusCode
          json shouldEqual e.json
        case None    =>
          if (revision == 0L)
            response.status shouldEqual StatusCodes.Created
          else
            response.status shouldEqual StatusCodes.OK

          filterMetadataKeys(json) shouldEqual createOrgRespJson(
            id,
            revision + 1L,
            "orgs",
            "Organization",
            authenticated,
            "organizations"
          )
      }
    }
  }

  def deprecateOrganization(id: String, authenticated: Authenticated): Task[Assertion] =
    cl.get[Json](s"/orgs/$id", authenticated) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val rev = admin._rev.getOption(json).value
      cl.delete[Json](s"/orgs/$id?rev=$rev", authenticated) { (deleteJson, deleteResponse) =>
        deleteResponse.status shouldEqual StatusCodes.OK
        filterMetadataKeys(deleteJson) shouldEqual createOrgRespJson(
          id,
          rev + 1L,
          "orgs",
          "Organization",
          authenticated,
          "organizations",
          deprecated = true
        )
      }.runSyncUnsafe()
    }

  private[tests] val startPool = Vector.range('a', 'z')
  private[tests] val pool      = Vector.range('a', 'z') ++ Vector.range('0', '9') :+ '_' :+ '-'

  private[tests] def randomProjectPrefix = genString(1, startPool) + genString(10, pool)

  def projectPayload(
      path: String = "/admin/projects/create.json",
      nxv: String = randomProjectPrefix,
      person: String = randomProjectPrefix,
      description: String = genString(),
      base: String = s"${config.deltaUri.toString()}/${genString()}/",
      vocab: String = s"${config.deltaUri.toString()}/${genString()}/"
  ): Json =
    jsonContentOf(
      path,
      "nxv-prefix"    -> nxv,
      "person-prefix" -> person,
      "description"   -> description,
      "base"          -> base,
      "vocab"         -> vocab
    )

  def createProject(
      orgId: String,
      projectId: String,
      json: Json,
      authenticated: Authenticated,
      expectedResponse: Option[ExpectedResponse] = None
  ): Task[Assertion] =
    updateProject(orgId, projectId, json, authenticated, 0L, expectedResponse)

  def updateProject(
      orgId: String,
      projectId: String,
      payload: Json,
      authenticated: Authenticated,
      revision: Long,
      expectedResponse: Option[ExpectedResponse] = None
  ): Task[Assertion] =
    cl.put[Json](s"/projects/$orgId/$projectId${queryParams(revision)}", payload, authenticated) { (json, response) =>
      logger.info(s"Creating/updating project $orgId/$projectId at revision $revision")
      expectedResponse match {
        case Some(e) =>
          response.status shouldEqual e.statusCode
          json shouldEqual e.json
        case None    =>
          if (revision == 0L)
            response.status shouldEqual StatusCodes.Created
          else
            response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) shouldEqual createProjectRespJson(
            projectId,
            orgId,
            revision + 1L,
            authenticated = authenticated,
            schema = "projects"
          )
      }

    }

  def getUuids(orgId: String, projectId: String, identity: Identity): Task[(String, String)] =
    for {
      orgUuid     <- cl.getJson[Json](s"/orgs/$orgId", identity)
      projectUuid <- cl.getJson[Json](s"/projects/$orgId/$projectId", identity)
    } yield (
      _uuid.getOption(orgUuid).value,
      _uuid.getOption(projectUuid).value
    )

}
