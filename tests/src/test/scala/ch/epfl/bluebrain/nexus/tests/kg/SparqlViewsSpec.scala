package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.views.ScoobyDoo
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Views}
import io.circe.Json
import monix.execution.Scheduler.Implicits.global

class SparqlViewsSpec extends BaseSpec with EitherValuable with CirceEq {

  private val orgId  = genId()
  private val projId = genId()
  val fullId         = s"$orgId/$projId"

  private val projId2 = genId()
  val fullId2         = s"$orgId/$projId2"

  val projects = List(fullId, fullId2)

  "creating projects" should {
    "add necessary permissions for user" in {
      for {
        _ <- aclDsl.addPermission("/", ScoobyDoo, Organizations.Create)
        _ <- aclDsl.addPermissionAnonymous(s"/$fullId2", Views.Query)
      } yield succeed
    }

    "succeed if payload is correct" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, ScoobyDoo)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), ScoobyDoo)
        _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(name = fullId2), ScoobyDoo)
      } yield succeed
    }

    "wait until in project resolver is created" in {
      eventually {
        deltaClient.get[Json](s"/resolvers/$fullId", ScoobyDoo) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 1L
        }
      }
    }
  }

  "creating the view" should {
    "create a context" in {
      val payload = jsonContentOf("/kg/views/context.json")

      projects.parTraverse { project =>
        deltaClient.put[Json](s"/resources/$project/resource/test-resource:context", payload, ScoobyDoo) {
          (_, response) =>
            response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "create an Sparql view that index tags" in {
      val payload = jsonContentOf("/kg/views/sparql-view.json")
      deltaClient.put[Json](s"/views/$fullId/test-resource:testSparqlView", payload, ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "get the created SparqlView" in {
      deltaClient.get[Json](s"/views/$fullId/test-resource:testSparqlView", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK

        val expected = jsonContentOf(
          "/kg/views/sparql-view-response.json",
          replacements(
            ScoobyDoo,
            "id"             -> "https://dev.nexus.test.com/simplified-resource/testSparqlView",
            "resources"      -> s"${config.deltaUri}/views/$fullId/test-resource:testSparqlView",
            "project-parent" -> s"${config.deltaUri}/projects/$fullId"
          ): _*
        )

        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "create an AggregateSparqlView" in {
      val payload = jsonContentOf("/kg/views/agg-sparql-view.json", "project1" -> fullId, "project2" -> fullId2)

      deltaClient.put[Json](s"/views/$fullId2/test-resource:testAggView", payload, ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "get an AggregateSparqlView" in {
      deltaClient.get[Json](s"/views/$fullId2/test-resource:testAggView", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "/kg/views/agg-sparql-view-response.json",
          replacements(
            ScoobyDoo,
            "id"             -> "https://dev.nexus.test.com/simplified-resource/testAggView",
            "resources"      -> s"${config.deltaUri}/views/$fullId2/test-resource:testAggView",
            "project-parent" -> s"${config.deltaUri}/projects/$fullId2",
            "project1"       -> fullId,
            "project2"       -> fullId2
          ): _*
        )

        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "post instances" in {
      (1 to 8).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"/kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        val projectId    = if (i > 5) fullId2 else fullId

        deltaClient.put[Json](
          s"/resources/$projectId/resource/patchedcell:$unprefixedId",
          payload,
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "wait until in project view is indexed" in eventually {
      deltaClient.get[Json](s"/views/$fullId", ScoobyDoo) { (json, response) =>
        _total.getOption(json).value shouldEqual 4
        response.status shouldEqual StatusCodes.OK
      }
    }

    "wait until all instances are indexed in default view of project 2" in eventually {
      deltaClient.get[Json](s"/resources/$fullId2/resource", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        _total.getOption(json).value shouldEqual 4
      }
    }

    val query =
      """
        |prefix nsg: <https://bbp-nexus.epfl.ch/vocabs/bbp/neurosciencegraph/core/v0.1.0/>
        |
        |select ?s where {
        |  ?s nsg:brainLocation / nsg:brainRegion <http://www.parcellation.org/0000013>
        |}
        |order by ?s
      """.stripMargin

    "search instances in SPARQL endpoint in project 1" in eventually {
      deltaClient.sparqlQuery[Json](s"/views/$fullId/nxv:defaultSparqlIndex/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("/kg/views/sparql-search-response.json")
      }
    }

    "search instances in SPARQL endpoint in project 2" in {
      deltaClient.sparqlQuery[Json](s"/views/$fullId2/nxv:defaultSparqlIndex/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("/kg/views/sparql-search-response-2.json")
      }
    }

    "search instances in AggregateSparqlView when logged" in {
      deltaClient.sparqlQuery[Json](s"/views/$fullId2/test-resource:testAggView/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json should equalIgnoreArrayOrder(jsonContentOf("/kg/views/sparql-search-response-aggregated.json"))
      }
    }

    "search instances in AggregateSparqlView as anonymous" in {
      deltaClient.sparqlQuery[Json](s"/views/$fullId2/test-resource:testAggView/sparql", query, Anonymous) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json should equalIgnoreArrayOrder(jsonContentOf("/kg/views/sparql-search-response-2.json"))
      }
    }

    "fetch statistics for defaultSparqlIndex" in eventually {
      deltaClient.get[Json](s"/views/$fullId/nxv:defaultSparqlIndex/statistics", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "/kg/views/statistics.json",
          "total"     -> "13",
          "processed" -> "13",
          "evaluated" -> "13",
          "discarded" -> "0",
          "remaining" -> "0"
        )
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "search instances in SPARQL endpoint in project 1 with custom SparqlView" in {
      deltaClient.sparqlQuery[Json](s"/views/$fullId/test-resource:testSparqlView/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("/kg/views/sparql-search-response-empty.json")
      }
    }

    "tag resources resource" in {
      (1 to 5).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"/kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        deltaClient.post[Json](
          s"/resources/$fullId/resource/patchedcell:$unprefixedId/tags?rev=1",
          json"""{ "rev": 1, "tag": "one"}""",
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "search instances in SPARQL endpoint in project 1 with custom SparqlView after tags added" in {
      eventually {
        deltaClient.sparqlQuery[Json](s"/views/$fullId/test-resource:testSparqlView/sparql", query, ScoobyDoo) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            json shouldEqual jsonContentOf("/kg/views/sparql-search-response.json")
        }
      }
    }

    "remove @type on a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("/kg/views/instances/instance1.json"))
      val id           = `@id`.getOption(payload).value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")

      deltaClient.put[Json](
        s"/resources/$fullId/_/patchedcell:$unprefixedId?rev=2",
        filterKey("@id")(payload),
        ScoobyDoo
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "deprecate a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("/kg/views/instances/instance2.json"))
      val id           = payload.asObject.value("@id").value.asString.value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
      deltaClient.delete[Json](s"/resources/$fullId/_/patchedcell:$unprefixedId?rev=2", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "create a another SPARQL view" in {
      val payload = jsonContentOf("/kg/views/sparql-view.json")
      deltaClient.put[Json](s"/views/$fullId/test-resource:testSparqlView2", payload, ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "update a new SPARQL view with indexing=sync" in {
      val payload = jsonContentOf("/kg/views/sparql-view.json").mapObject(
        _.remove("resourceTag").remove("resourceTypes").remove("resourceSchemas")
      )
      deltaClient.put[Json](s"/views/$fullId/test-resource:testSparqlView2?rev=1&indexing=sync", payload, ScoobyDoo) {
        (_, response) =>
          response.status shouldEqual StatusCodes.OK
      }
    }

  }
}
