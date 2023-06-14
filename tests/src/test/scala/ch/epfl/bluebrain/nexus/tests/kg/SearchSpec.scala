package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Views}
import io.circe.Json
import io.circe.optics.JsonPath._

class SearchSpec extends BaseSpec {

  private val orgId   = genId()
  private val projId1 = genId()
  private val projId2 = genId()
  private val id1     = s"$orgId/$projId1"
  private val id2     = s"$orgId/$projId2"
  val projects        = List(id1, id2)

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Rick, Organizations.Create)
      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id1), Rick)
      _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id2), Rick)
      // post some resources
      _ <- postResource("/kg/search/neuroshapes.json")
      _ <- postResource("/kg/search/bbp-neuroshapes.json")
      _ <- postResource("/kg/search/trace.json")
    } yield ()

    setup.accepted
  }

  "searching resources" should {
    "list all resources" in eventually {
      for {
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = getEsSource(body)
               _projects.getAll(sources).toSet shouldEqual Set(id1, id2)
             }

      } yield succeed
    }


    "list resources only from the projects the user has access to" in {
      for {
        _ <- deleteRickPermissionsOnProj2()
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = getEsSource(body)
               _projects.getAll(sources).toSet shouldEqual Set(id1)
             }

      } yield succeed
    }
  }

  "config endpoint" should {

    "return config" in {
      deltaClient.get[Json]("/search/config", Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        body shouldEqual jsonContentOf("/kg/search/config.json")
      }
    }
  }

  /** Post resource across all projects defined in the test suite */
  private def postResource(resourcePath: String) =
    projects.parTraverse { project =>
      for {
        _ <- deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf(resourcePath), Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

  /** Lens to get all project labels in a given json */
  private val _projects = root.each.project.label.string

  /** Get the `_source` from an ES json response */
  private def getEsSource(json: Json) = Json.fromValues(json.findAllByKey("_source"))

  private def deleteRickPermissionsOnProj2() =
    for {
      _ <- aclDsl.deletePermission(s"/$orgId", Rick, Views.Query)
      _ <- aclDsl.deletePermission(s"/$id2", Rick, Views.Query)
    } yield succeed
}
