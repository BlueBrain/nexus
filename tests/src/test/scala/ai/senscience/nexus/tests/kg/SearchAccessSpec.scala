package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.iam.types.Permission.{Organizations, Resources, Views}
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Optics}
import akka.http.scaladsl.model.StatusCodes
import cats.implicits.*
import io.circe.Json
import io.circe.optics.JsonPath.*
import tags.BlazegraphOnly

@BlazegraphOnly
class SearchAccessSpec extends BaseIntegrationSpec {

  private val orgId    = genId()
  private val projId1  = genId()
  private val projId2  = genId()
  private val project1 = s"$orgId/$projId1"
  private val project2 = s"$orgId/$projId2"
  private val projects = List(project1, project2)

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.cleanAcls(Rick)
      _ <- aclDsl.addPermission("/", Rick, Organizations.Create)

      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProject(orgId, projId1, ProjectPayload.generateBbp(project1), authenticated = Rick)
      _ <- adminDsl.createProject(orgId, projId2, ProjectPayload.generateBbp(project2), authenticated = Rick)

      _ <- aclDsl.addPermission(s"/$orgId", Rick, Resources.Read)
      _ <- aclDsl.addPermission(s"/$orgId/$projId1", Rick, Resources.Read)
      _ <- aclDsl.addPermission(s"/$orgId/$projId2", Rick, Resources.Read)

      _ <- postResource("kg/context/neuroshapes.json")
      _ <- postResource("kg/context/bbp-neuroshapes.json")
      _ <- postResource("kg/search/data/trace.json")
    } yield ()

    setup.accepted
  }

  "searching resources" should {
    "list all resources" in eventually {
      for {
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = getEsSource(body)
               _projects.getAll(sources).toSet shouldEqual Set(project1, project2)
             }
      } yield succeed
    }

    "list resources only from the projects the user has access to" in {
      for {
        _ <- deleteRickPermissionsOnProj2()
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = getEsSource(body)
               _projects.getAll(sources).toSet shouldEqual Set(project1)
             }

      } yield succeed
    }
  }

  "config endpoint" should {

    "return config" in {
      deltaClient.get[Json]("/search/config", Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        body.asObject.value.keys.toSet shouldEqual Set("fields", "layouts")
      }
    }
  }

  "Api Mapping" should {
    "be defined" in {
      val searchViewId = "https://bluebrain.github.io/nexus/vocabulary/searchView"
      deltaClient.get[Json](s"/views/$project1/search", Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        Optics.`@id`.getOption(body).value shouldEqual searchViewId
      }
    }
  }

  /** Post resource across all projects defined in the test suite */
  private def postResource(resourcePath: String) =
    projects.parTraverse { project =>
      deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf(resourcePath), Rick) { expectCreated }
    }

  /** Lens to get all project labels in a given json */
  private val _projects = root.each.project.string

  /** Get the `_source` from an ES json response */
  private def getEsSource(json: Json) = Json.fromValues(json.findAllByKey("_source"))

  private def deleteRickPermissionsOnProj2() =
    for {
      _ <- aclDsl.deletePermission(s"/$orgId", Rick, Views.Query)
      _ <- aclDsl.deletePermission(s"/$project2", Rick, Views.Query)
    } yield succeed
}
