package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.aggregations.{Charlie, Rose}
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.iam.types.Permission.{Organizations, Resources, Views}
import ai.senscience.nexus.tests.resources.SimpleResource
import akka.http.scaladsl.model.StatusCodes
import cats.syntax.all.*
import io.circe.Json

final class AggregationsSpec extends BaseIntegrationSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val ref11  = s"$org1/$proj11"
  private val ref12  = s"$org1/$proj12"

  private val org2   = genId()
  private val proj21 = genId()
  private val ref21  = s"$org2/$proj21"

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Charlie, Organizations.Create)
      // First org and projects
      _ <- adminDsl.createOrganization(org1, org1, Charlie)
      _ <- adminDsl.createProject(org1, proj11, ProjectPayload.generate(proj11), Charlie)
      _ <- adminDsl.createProject(org1, proj12, ProjectPayload.generate(proj12), Charlie)
      // Second org and projects
      _ <- adminDsl.createOrganization(org2, org2, Charlie)
      _ <- adminDsl.createProject(org2, proj21, ProjectPayload.generate(proj21), Charlie)
      _ <- aclDsl.addPermission(s"/$ref12", Rose, Resources.Read)
      _ <- aclDsl.addPermission(s"/$ref12", Rose, Views.Query)
    } yield ()

    val postResources = for {
      resourcePayload <- SimpleResource.sourcePayload(5)
      resources        = List(ref11 -> "r11_1", ref11 -> "r11_2", ref12 -> "r12_1", ref21 -> "r21_1")
      _               <- resources.parTraverse { case (proj, id) =>
                           deltaClient.put[Json](s"/resources/$proj/_/$id", resourcePayload, Charlie)(expectCreated)
                         }
    } yield ()

    (setup >> postResources).accepted
  }

  "Aggregating resources within a project" should {

    "get an error if the user has no access" in {
      deltaClient.get[Json](s"/resources/$ref11?aggregations=true", Rose) { expectForbidden }
    }

    "aggregate correctly for a user that has project permissions" in eventually {
      val expected = jsonContentOf(
        "kg/aggregations/project-aggregation.json",
        "org"     -> org1,
        "project" -> proj11
      )
      deltaClient.get[Json](s"/resources/$ref11?aggregations=true", Charlie) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }
  }

  "Aggregating resources within an org" should {

    "get an error if the user has no access on the org" in {
      deltaClient.get[Json](s"/resources/$org2?aggregations=true", Rose) { expectForbidden }
    }

    "aggregate correctly for a user that has access" in eventually {
      val expected = jsonContentOf(
        "kg/aggregations/org-aggregation.json",
        "org1"   -> org1,
        "proj11" -> proj11,
        "proj12" -> proj12
      )
      deltaClient.get[Json](s"/resources/$org1?aggregations=true", Charlie) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }

  }

  "Aggregating resources within all accessible projects in the system" should {

    "get an error for anonymous" in {
      deltaClient.get[Json](s"/resources?aggregations=true", Anonymous) { expectForbidden }
    }

    "aggregate correctly for a user that has permissions on at least one project" in eventually {
      val expected = jsonContentOf(
        "kg/aggregations/root-aggregation.json",
        "org1"   -> org1,
        "org2"   -> org2,
        "proj11" -> proj11,
        "proj12" -> proj12,
        "proj21" -> proj21
      )
      deltaClient.get[Json](s"/resources?aggregations=true", Charlie) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }

  }

}
