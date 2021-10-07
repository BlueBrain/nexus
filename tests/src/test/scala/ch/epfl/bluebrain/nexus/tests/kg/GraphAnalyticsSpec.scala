package ch.epfl.bluebrain.nexus.tests.kg

import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.projects.Bojack
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Projects, Resources}
import io.circe.Json

final class GraphAnalyticsSpec extends BaseSpec with CirceEq {
  private val org  = genId()
  private val proj = genId()
  private val ref  = s"$org/$proj"

  "Setting up" should {
    "succeed in setting up org, project and acls" in {
      for {
        _ <- aclDsl.addPermissions("/", Bojack, Set(Organizations.Create, Projects.Delete, Resources.Read, Events.Read))
        _ <- adminDsl.createOrganization(org, org, Bojack)
        _ <- adminDsl.createProject(org, proj, kgDsl.projectJson(name = proj), Bojack)
      } yield succeed
    }
  }

  "GraphAnalytics" should {
    "add resources" in {
      for {
        _ <- deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("/kg/graph-analytics/person1.json"), Bojack)(
               expectCreated
             )
        _ <- deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("/kg/graph-analytics/person2.json"), Bojack)(
               expectCreated
             )
        _ <- deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("/kg/graph-analytics/person3.json"), Bojack)(
               expectCreated
             )
        _ <-
          deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("/kg/graph-analytics/organization.json"), Bojack)(
            expectCreated
          )
      } yield succeed
    }

    "fetch relationships" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/relationships", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("/kg/graph-analytics/relationships.json")
      }
    }

    "fetch properties" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/properties/http%3A%2F%2Fschema.org%2FPerson", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("/kg/graph-analytics/properties-person.json")
      }
    }

    "update resources" in {
      for {
        _ <- deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("/kg/graph-analytics/person4.json"), Bojack)(
               expectCreated
             )
        _ <-
          deltaClient.put[Json](
            s"/resources/$ref/_/http%3A%2F%2Fexample.com%2Fepfl?rev=1",
            jsonContentOf("/kg/graph-analytics/organization-updated.json"),
            Bojack
          )(
            expectOk
          )
      } yield succeed
    }
    "fetch updated relationships" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/relationships", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("/kg/graph-analytics/relationships-updated.json")
      }
    }
    "fetch updated properties" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/properties/http%3A%2F%2Fschema.org%2FPerson", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("/kg/graph-analytics/properties-person-updated.json")
      }
    }
  }

}
