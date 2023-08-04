package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, SchemaPayload}
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.aggregations.{Charlie, Rose}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources, Views}
import io.circe.Json
import org.scalatest.Inspectors

final class AggregationsSpec extends BaseSpec with Inspectors with EitherValuable with CirceEq {

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
      _ <- adminDsl.createProject(org1, proj11, kgDsl.projectJson(name = proj11), Charlie)
      _ <- adminDsl.createProject(org1, proj12, kgDsl.projectJson(name = proj12), Charlie)
      // Second org and projects
      _ <- adminDsl.createOrganization(org2, org2, Charlie)
      _ <- adminDsl.createProject(org2, proj21, kgDsl.projectJson(name = proj21), Charlie)
      _ <- aclDsl.addPermission(s"/$ref12", Rose, Resources.Read)
      _ <- aclDsl.addPermission(s"/$ref12", Rose, Views.Query)
    } yield ()

    val resourcePayload =
      jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority" -> "5"
      )
    val schemaPayload   = SchemaPayload.loadSimple()
    val postResources   = for {
      // Creation
      _ <- deltaClient.put[Json](s"/resources/$ref11/_/resource11", resourcePayload, Charlie)(expectCreated)
      _ <- deltaClient.put[Json](s"/schemas/$ref11/test-schema", schemaPayload, Charlie)(expectCreated)
      _ <- deltaClient.put[Json](s"/resources/$ref11/test-schema/resource11_with_schema", resourcePayload, Charlie)(
             expectCreated
           )
      _ <- deltaClient.put[Json](s"/resources/$ref12/_/resource12", resourcePayload, Charlie)(expectCreated)
      _ <- deltaClient.put[Json](s"/resources/$ref21/_/resource21", resourcePayload, Charlie)(expectCreated)
    } yield ()

    (setup >> postResources).accepted
  }

  "Aggregating resources within a project" should {

    "get an error if the user has no access" in {

      deltaClient.get[Json](s"/resources/$ref11?aggregations=true", Rose) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "aggregate correctly for a user that has project permissions" in eventually {
      val expected = jsonContentOf(
        "/kg/aggregations/project-aggregation.json",
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
      deltaClient.get[Json](s"/resources/$org2?aggregations=true", Rose) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "aggregate correctly for a user that has " in eventually {
      val expected = jsonContentOf(
        "/kg/aggregations/org-aggregation.json",
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
      deltaClient.get[Json](s"/resources?aggregations=true", Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "aggregate correctly for a user that has permissions on at least one project" in eventually {
      val expected = jsonContentOf(
        "/kg/aggregations/root-aggregation.json",
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
