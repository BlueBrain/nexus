package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.Tags.SearchTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import io.circe.Json

class SearchSpec extends BaseSpec {

  private val orgId   = genId()
  private val projId1 = genId()
  private val id1     = s"$orgId/$projId1"

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
      } yield succeed
    }

    "create contexts" taggedAs SearchTag in {
      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/neuroshapes.json"), Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/bbp-neuroshapes.json"), Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
      } yield succeed

    }
  }

  "adding resources" should {

    "work" taggedAs SearchTag in {
      for {
        _ <-
          deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/nm1.json"), Rick) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/agent1.json"), Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/agent2.json"), Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
        _ <-
          deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/org1.json"), Rick) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", jsonContentOf("/kg/search/trace1.json"), Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }
  }

}
