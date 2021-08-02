package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.Tags.SearchTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Views}
import io.circe.Json
import io.circe.literal._

import java.net.URLEncoder

class SearchSpec extends BaseSpec {

  private val orgId   = genId()
  private val projId1 = genId()
  private val projId2 = genId()
  private val id1     = s"$orgId/$projId1"
  private val id2     = s"$orgId/$projId2"
  val projects        = List(id1, id2)

  "init project" should {

    "add necessary permissions for user" taggedAs SearchTag in {
      aclDsl.addPermission(
        "/",
        Rick,
        Organizations.Create
      )
    }

    "succeed if payload is correct" taggedAs SearchTag in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Rick)
        _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id1), Rick)
        _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id2), Rick)
      } yield succeed
    }

    "create contexts" taggedAs SearchTag in {
      projects.parTraverse { project =>
        for {
          _ <- deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf("/kg/search/neuroshapes.json"), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
          _ <-
            deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf("/kg/search/bbp-neuroshapes.json"), Rick) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
        } yield succeed
      }

    }
  }

  "adding resources" should {

    "work" taggedAs SearchTag in {
      projects.parTraverse { project =>
        for {
          _ <- deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf("/kg/search/agent1.json"), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
          _ <- deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf("/kg/search/agent2.json"), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
          _ <-
            deltaClient.post[Json](s"/resources/$project/_/", jsonContentOf("/kg/search/org1.json"), Rick) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
          _ <-
            deltaClient.post[Json](
              s"/resources/$project/_/",
              jsonContentOf("/kg/search/nm1.json", replacements(Rick, "project" -> project): _*),
              Rick
            ) { (_, response) =>
              response.status shouldEqual StatusCodes.Created
            }
          _ <- deltaClient.post[Json](
                 s"/resources/$project/_/",
                 jsonContentOf("/kg/search/trace1.json", replacements(Rick, "project" -> project): _*),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        } yield succeed
      }
    }
  }

  "searching resources" should {
    "list all resources" taggedAs SearchTag in eventually {
      for {
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = Json.fromValues(body.findAllByKey("_source"))

               val expectedSources = projects.sorted.flatMap { project =>
                 List(
                   jsonContentOf(
                     "/kg/search/nm1-indexed.json",
                     replacements(
                       Rick,
                       "project"           -> project,
                       "projectUrlEncoded" -> URLEncoder.encode(project, "UTF8")
                     ): _*
                   ),
                   jsonContentOf(
                     "/kg/search/trace1-indexed.json",
                     replacements(
                       Rick,
                       "project"           -> project,
                       "projectUrlEncoded" -> URLEncoder.encode(project, "UTF8")
                     ): _*
                   )
                 )
               }
               sources should equalIgnoreArrayOrder(Json.fromValues(expectedSources))
             }

      } yield succeed
    }

    "remove permissions" taggedAs SearchTag in {
      for {
        _ <- aclDsl.deletePermission(
               s"/$orgId",
               Rick,
               Views.Query
             )
        _ <- aclDsl
               .deletePermission(
                 s"/$id2",
                 Rick,
                 Views.Query
               )
      } yield succeed
    }

    "list resources only from the projects the user has access to" taggedAs SearchTag in {
      for {
        _ <- deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
               response.status shouldEqual StatusCodes.OK
               val sources = Json.fromValues(body.findAllByKey("_source"))

               val expectedSources =
                 List(
                   jsonContentOf(
                     "/kg/search/nm1-indexed.json",
                     replacements(
                       Rick,
                       "project"           -> id1,
                       "projectUrlEncoded" -> URLEncoder.encode(id1, "UTF8")
                     ): _*
                   ),
                   jsonContentOf(
                     "/kg/search/trace1-indexed.json",
                     replacements(
                       Rick,
                       "project"           -> id1,
                       "projectUrlEncoded" -> URLEncoder.encode(id1, "UTF8")
                     ): _*
                   )
                 )
               sources should equalIgnoreArrayOrder(Json.fromValues(expectedSources))
             }

      } yield succeed
    }
  }

  "config endpoint" should {

    "return config" taggedAs SearchTag in {
      deltaClient.get[Json]("/search/config", Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        body shouldEqual jsonContentOf("/kg/search/config.json")
      }
    }
  }
}
