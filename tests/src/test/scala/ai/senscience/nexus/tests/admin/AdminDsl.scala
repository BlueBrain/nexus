package ai.senscience.nexus.tests.admin

import ai.senscience.nexus.tests.HttpClient
import ai.senscience.nexus.tests.Identity.Authenticated
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.config.TestsConfig
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.Generators
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}

class AdminDsl(cl: HttpClient, config: TestsConfig)
    extends Generators
    with CirceUnmarshalling
    with Matchers
    with OptionValues {

  private val logger = Logger[this.type]
  private val loader = ClasspathResourceLoader()

  private def orgPayload(description: String): IO[Json] =
    loader.jsonContentOf("admin/orgs/payload.json", "description" -> description)

  private def createOrgRespJson(
      id: String,
      rev: Int,
      tpe: String,
      `@type`: String,
      authenticated: Authenticated,
      schema: String,
      deprecated: Boolean = false
  ): IO[Json] = {
    val resp = Seq(
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
    loader.jsonContentOf("admin/org-response.json", resp*)
  }

  def createProjectRespJson(
      id: String,
      orgId: String,
      rev: Int,
      tpe: String = "projects",
      `@type`: String = "Project",
      authenticated: Authenticated,
      schema: String,
      deprecated: Boolean = false,
      markedForDeletion: Boolean = false
  ): IO[Json] = {
    val resp = Seq(
      "projectId"         -> id,
      "path"              -> tpe,
      "type"              -> `@type`,
      "rev"               -> rev.toString,
      "deltaUri"          -> config.deltaUri.toString(),
      "realm"             -> authenticated.realm.name,
      "user"              -> authenticated.name,
      "orgId"             -> orgId,
      "deprecated"        -> deprecated.toString,
      "markedForDeletion" -> markedForDeletion.toString,
      "schema"            -> schema
    )
    loader.jsonContentOf("admin/project-response.json", resp*)
  }

  private def queryParams(rev: Int) =
    if (rev == 0L) {
      ""
    } else {
      s"?rev=$rev"
    }

  def createOrganization(
      id: String,
      description: String,
      authenticated: Authenticated,
      expectedStatus: Option[StatusCode] = None,
      ignoreConflict: Boolean = false
  ): IO[Assertion] =
    updateOrganization(id, description, authenticated, 0, expectedStatus, ignoreConflict)

  def updateOrganization(
      id: String,
      description: String,
      authenticated: Authenticated,
      rev: Int,
      expectedStatus: Option[StatusCode] = None,
      ignoreConflict: Boolean = false
  ): IO[Assertion] = {
    for {
      payload  <- orgPayload(description)
      expected <- createOrgRespJson(
                    id,
                    rev + 1,
                    "orgs",
                    "Organization",
                    authenticated,
                    "organizations"
                  )
      result   <- cl.put[Json](s"/orgs/$id${queryParams(rev)}", payload, authenticated) { (json, response) =>
                    expectedStatus match {
                      case Some(status) =>
                        response.status shouldEqual status
                      case None         =>
                        if (ignoreConflict && response.status == StatusCodes.Conflict)
                          succeed
                        else {
                          if (rev == 0L)
                            response.status shouldEqual StatusCodes.Created
                          else
                            response.status shouldEqual StatusCodes.OK

                          filterMetadataKeys(json) shouldEqual expected
                        }
                    }
                  }
    } yield result
  }

  def deprecateOrganization(id: String, authenticated: Authenticated): IO[Assertion] =
    cl.get[Json](s"/orgs/$id", authenticated) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val rev = admin._rev.getOption(json).value
      (for {
        expected <- createOrgRespJson(
                      id,
                      rev + 1,
                      "orgs",
                      "Organization",
                      authenticated,
                      "organizations",
                      deprecated = true
                    )
        result   <- cl.delete[Json](s"/orgs/$id?rev=$rev", authenticated) { (deleteJson, deleteResponse) =>
                      deleteResponse.status shouldEqual StatusCodes.OK
                      filterMetadataKeys(deleteJson) shouldEqual expected
                    }
      } yield result).unsafeRunSync()
    }

  private[tests] val startPool = Vector.range('a', 'z')
  private[tests] val pool      = Vector.range('a', 'z') ++ Vector.range('0', '9') :+ '_' :+ '-'

  private[tests] def randomProjectPrefix = genString(1, startPool) + genString(10, pool)

  def createProjectWithName(
      orgId: String,
      projectId: String,
      name: String,
      authenticated: Authenticated
  )(implicit config: TestsConfig): IO[Assertion] =
    createProject(orgId, projectId, ProjectPayload.generate(name), authenticated)

  def createProject(
      orgId: String,
      projectId: String,
      payload: ProjectPayload,
      authenticated: Authenticated,
      expectedResponse: Option[StatusCode] = None
  ): IO[Assertion] =
    updateProject(orgId, projectId, payload, authenticated, 0, expectedResponse)

  def updateProject(
      orgId: String,
      projectId: String,
      payload: ProjectPayload,
      authenticated: Authenticated,
      rev: Int,
      expectedResponse: Option[StatusCode] = None
  ): IO[Assertion] = {
    for {
      _        <- logger.info(s"Creating/updating project $orgId/$projectId at revision $rev")
      expected <- createProjectRespJson(
                    projectId,
                    orgId,
                    rev + 1,
                    authenticated = authenticated,
                    schema = "projects"
                  )
      result   <-
        cl.put[Json](s"/projects/$orgId/$projectId${queryParams(rev)}", payload.asJson, authenticated) {
          (json, response) =>
            expectedResponse match {
              case Some(status) =>
                response.status shouldEqual status
              case None         =>
                if (rev == 0)
                  response.status shouldEqual StatusCodes.Created
                else
                  response.status shouldEqual StatusCodes.OK

                filterProjectMetadataKeys(json) shouldEqual expected
            }
        }
    } yield result
  }

}
