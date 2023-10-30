package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.Bob
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import io.circe.Json
//import monix.execution.Scheduler

class HammerTest extends BaseIntegrationSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val ref11  = s"$org1/$proj11"

  private val payload  = json"""{ "cat": "animal" }"""
  //  implicit private val sc: Scheduler = Scheduler.global

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProject(org1, proj11, kgDsl.projectJson(name = proj11), Bob)
    } yield ()

    val createResources = for {
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/", payload, Bob)(expectCreated)
    } yield ()

    (setup >> createResources).accepted
  }

  "Hammering an endpoint" should {

    "not break delta" in {
      val postInParallel = (1 to 15).toList.parTraverse { i =>
        deltaClient.put[Json](s"/resources/$ref11/_/res$i?indexing=sync", payload, Bob) { (_, response) =>
          println(s"Iteration $i")
          response.status shouldEqual StatusCodes.Created
        }
      }

      postInParallel >>
        deltaClient.get[Json](s"/resources/$ref11/_/res1", Bob) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
    }

  }

}