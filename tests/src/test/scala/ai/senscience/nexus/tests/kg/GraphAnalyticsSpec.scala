package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.projects.Bojack
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.iam.types.Permission.{Events, Organizations, Projects, Resources}
import akka.http.scaladsl.model.StatusCodes
import cats.implicits.*
import io.circe.Json

final class GraphAnalyticsSpec extends BaseIntegrationSpec {
  private val org          = genId()
  private val proj         = genId()
  private val ref          = s"$org/$proj"
  private val matchPerson1 = json"""{ "query": { "match": { "@id" : "http://example.com/person1" } } }"""

  private def extractSources(json: Json) =
    json.hcursor
      .downField("hits")
      .get[Vector[Json]]("hits")
      .flatMap(seq => seq.traverse(_.hcursor.get[Json]("_source")))
      .rightValue

  "Setting up" should {
    "succeed in setting up org, project and acls" in {
      for {
        _ <- aclDsl.addPermissions("/", Bojack, Set(Organizations.Create, Projects.Delete, Resources.Read, Events.Read))
        _ <- adminDsl.createOrganization(org, org, Bojack)
        _ <- adminDsl.createProjectWithName(org, proj, name = proj, Bojack)
      } yield succeed
    }
  }

  "GraphAnalytics" should {
    "add resources" in {
      def postResource(resourcePath: String) =
        deltaClient.post[Json](s"/resources/$ref/", jsonContentOf(resourcePath), Bojack)(expectCreated)

      for {
        _ <- postResource("kg/graph-analytics/context-test.json")
        _ <- postResource("kg/graph-analytics/person1.json")
        _ <- postResource("kg/graph-analytics/person2.json")
        _ <- postResource("kg/graph-analytics/person3.json")
        _ <- postResource("kg/graph-analytics/organization.json")
      } yield succeed
    }

    "fetch relationships" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/relationships", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("kg/graph-analytics/relationships.json")
      }
    }

    "fetch properties" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/properties/http%3A%2F%2Fschema.org%2FPerson", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("kg/graph-analytics/properties-person.json")
      }
    }

    "update resources" in {
      for {
        _ <- deltaClient.post[Json](s"/resources/$ref/", jsonContentOf("kg/graph-analytics/person4.json"), Bojack)(
               expectCreated
             )
        _ <-
          deltaClient.put[Json](
            s"/resources/$ref/_/http%3A%2F%2Fexample.com%2Fepfl?rev=1",
            jsonContentOf("kg/graph-analytics/organization-updated.json"),
            Bojack
          )(
            expectOk
          )
      } yield succeed
    }
    "fetch updated relationships" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/relationships", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("kg/graph-analytics/relationships-updated.json")
      }
    }
    "fetch updated properties" in eventually {
      deltaClient.get[Json](s"/graph-analytics/$ref/properties/http%3A%2F%2Fschema.org%2FPerson", Bojack) { (json, _) =>
        json shouldEqual jsonContentOf("kg/graph-analytics/properties-person-updated.json")
      }
    }

    "fail to query when unauthorized" in {
      deltaClient.post[Json](s"/graph-analytics/$ref/_search", matchPerson1, Anonymous) { expectForbidden }
    }

    "query for person1" in eventually {
      val expected = jsonContentOf("kg/graph-analytics/es-hit-source-person1.json", "projectRef" -> ref)

      // We ignore fields that are time or project dependent.
      // "relationships" are ignored as its "found" field can sometimes be
      // missing and making the test flaky while not being relevant to what's being tested
      val filterSource = filterMetadataKeys andThen filterRealmKeys andThen
        filterKey("_project") andThen filterKey("relationships")

      deltaClient.post[Json](s"/graph-analytics/$ref/_search", matchPerson1, Bojack) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val sources = extractSources(json)
        sources.size shouldEqual 1
        filterSource(extractSources(json).head) shouldEqual filterSource(expected)
      }
    }
  }

}
