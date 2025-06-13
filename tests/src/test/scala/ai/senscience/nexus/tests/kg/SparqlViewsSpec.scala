package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.views.ScoobyDoo
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.iam.types.Permission.{Organizations, Views}
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits.*
import io.circe.Json
import org.scalatest.Assertion

class SparqlViewsSpec extends BaseIntegrationSpec {

  private val orgId  = genId()
  private val projId = genId()
  val project1       = s"$orgId/$projId"

  private val projId2 = genId()
  val project2        = s"$orgId/$projId2"

  private val projects = List(project1, project2)

  val idBase = "https://test.bbp.epfl.ch/"

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setPermissions = for {
      _ <- aclDsl.addPermission("/", ScoobyDoo, Organizations.Create)
      _ <- aclDsl.addPermissionAnonymous(s"/$project2", Views.Query)
    } yield succeed

    val createProjects = for {
      _ <- adminDsl.createOrganization(orgId, orgId, ScoobyDoo)
      _ <- adminDsl.createProjectWithName(orgId, projId, name = project1, ScoobyDoo)
      _ <- adminDsl.createProjectWithName(orgId, projId2, name = project2, ScoobyDoo)
    } yield succeed

    (setPermissions >> createProjects).accepted

    // wait until in project resolver is created
    eventually {
      deltaClient.get[Json](s"/resolvers/$project1", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        _total.getOption(json).value shouldEqual 1L
      }
    }

    ()
  }

  "creating the view" should {
    "create a context" in {
      val payload = jsonContentOf("kg/views/context.json")

      projects.parTraverse { project =>
        deltaClient.put[Json](s"/resources/$project/resource/test-resource:context", payload, ScoobyDoo) {
          expectCreated
        }
      }
    }

    "create an Sparql view that index tags" in {
      val payload = jsonContentOf("kg/views/sparql-view.json")
      deltaClient.put[Json](s"/views/$project1/test-resource:cell-view", payload, ScoobyDoo) { expectCreated }
    }

    "get the created SparqlView" in {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val viewId   = "https://dev.nexus.test.com/simplified-resource/cell-view"
        val expected = jsonContentOf(
          "kg/views/sparql-view-response.json",
          replacements(
            ScoobyDoo,
            "id"             -> "https://dev.nexus.test.com/simplified-resource/cell-view",
            "self"           -> viewSelf(project1, viewId),
            "project-parent" -> project1
          )*
        )

        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "create an AggregateSparqlView" in {
      val payload = jsonContentOf("kg/views/agg-sparql-view.json", "project1" -> project1, "project2" -> project2)

      deltaClient.put[Json](s"/views/$project2/test-resource:agg-cell-view", payload, ScoobyDoo) { expectCreated }
    }

    "get an AggregateSparqlView" in {
      deltaClient.get[Json](s"/views/$project2/test-resource:agg-cell-view", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val viewId   = "https://dev.nexus.test.com/simplified-resource/agg-cell-view"
        val expected = jsonContentOf(
          "kg/views/agg-sparql-view-response.json",
          replacements(
            ScoobyDoo,
            "id"             -> viewId,
            "resources"      -> viewSelf(project2, viewId),
            "project-parent" -> project2,
            "project1"       -> project1,
            "project2"       -> project2
          )*
        )

        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "post instances" in {
      (1 to 8).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        val projectId    = if (i > 5) project2 else project1
        val indexingMode = if (i % 2 == 0) "sync" else "async"

        deltaClient.put[Json](
          s"/resources/$projectId/resource/patchedcell:$unprefixedId?indexing=$indexingMode",
          payload,
          ScoobyDoo
        ) { expectCreated }
      }
    }

    "wait until all instances are indexed in default view of project 2" in eventually {
      deltaClient.get[Json](s"/resources/$project2/resource", ScoobyDoo) { (json, response) =>
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
      deltaClient.sparqlQuery[Json](s"/views/$project1/nxv:defaultSparqlIndex/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("kg/views/sparql-search-response.json")
      }
    }

    "search instances in SPARQL endpoint in project 2" in eventually {
      deltaClient.sparqlQuery[Json](s"/views/$project2/nxv:defaultSparqlIndex/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("kg/views/sparql-search-response-2.json")
      }
    }

    "search instances in AggregateSparqlView when logged" in {
      deltaClient.sparqlQuery[Json](s"/views/$project2/test-resource:agg-cell-view/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json should equalIgnoreArrayOrder(jsonContentOf("kg/views/sparql-search-response-aggregated.json"))
      }
    }

    "search instances in AggregateSparqlView as anonymous" in {
      deltaClient.sparqlQuery[Json](s"/views/$project2/test-resource:agg-cell-view/sparql", query, Anonymous) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json should equalIgnoreArrayOrder(jsonContentOf("kg/views/sparql-search-response-2.json"))
      }
    }

    "fetch statistics for cell-view" in eventually {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view/statistics", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected =
          filterNestedKeys("delayInSeconds")(
            jsonContentOf(
              "kg/views/statistics.json",
              "total"     -> "0",
              "processed" -> "0",
              "evaluated" -> "0",
              "discarded" -> "0",
              "remaining" -> "0"
            )
          )

        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "fetch statistics for defaultSparqlIndex" in eventually {
      deltaClient.get[Json](s"/views/$project1/nxv:defaultSparqlIndex/statistics", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "kg/views/statistics.json",
          "total"     -> "6",
          "processed" -> "6",
          "evaluated" -> "6",
          "discarded" -> "0",
          "remaining" -> "0"
        )
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "get no instances in SPARQL endpoint in project 1 with cell-view" in {
      deltaClient.sparqlQuery[Json](s"/views/$project1/test-resource:cell-view/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("kg/views/sparql-search-response-empty.json")
      }
    }

    "tag resources" in {
      val tagPayload = json"""{ "rev": 1, "tag": "one"}"""
      (1 to 5).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        deltaClient
          .post[Json](s"/resources/$project1/resource/patchedcell:$unprefixedId/tags?rev=1", tagPayload, ScoobyDoo) {
            expectCreated
          }
      }
    }

    "fetch updated statistics for cell-view" in eventually {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view/statistics", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "kg/views/statistics.json",
          "total"     -> "5",
          "processed" -> "5",
          "evaluated" -> "5",
          "discarded" -> "0",
          "remaining" -> "0"
        )
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    val byTagQuery =
      """
        |prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
        |
        |select ?s where {
        |  ?s nxv:tags "one"
        |}
        |order by ?s
      """.stripMargin

    "search by tag in SPARQL endpoint in project 1 with default view" in eventually {
      deltaClient.sparqlQuery[Json](s"/views/$project1/nxv:defaultSparqlIndex/sparql", byTagQuery, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("kg/views/sparql-search-response-tagged.json")
      }
    }

    "search instances in SPARQL endpoint in project 1 with custom SparqlView after tags added" in {
      eventually {
        deltaClient.sparqlQuery[Json](s"/views/$project1/test-resource:cell-view/sparql", query, ScoobyDoo) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            json shouldEqual jsonContentOf("kg/views/sparql-search-response.json")
        }
      }
    }

    "delete tags" in {
      (1 to 5).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        deltaClient.delete[Json](s"/resources/$project1/resource/patchedcell:$unprefixedId/tags/one?rev=2", ScoobyDoo) {
          expectOk
        }
      }
    }

    "search instances in SPARQL endpoint in project 1 with custom SparqlView after tags are deleted" in eventually {
      deltaClient.sparqlQuery[Json](s"/views/$project1/test-resource:cell-view/sparql", query, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual jsonContentOf("kg/views/sparql-search-response-empty.json")
      }
    }

    "remove @type on a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("kg/views/instances/instance1.json"))
      val id           = `@id`.getOption(payload).value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")

      deltaClient.put[Json](
        s"/resources/$project1/_/patchedcell:$unprefixedId?rev=3",
        filterKey("@id")(payload),
        ScoobyDoo
      ) { expectOk }
    }

    "deprecate a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("kg/views/instances/instance2.json"))
      val id           = payload.asObject.value("@id").value.asString.value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
      deltaClient.delete[Json](s"/resources/$project1/_/patchedcell:$unprefixedId?rev=3", ScoobyDoo) { expectOk }
    }

    "create a another SPARQL view" in {
      val payload = jsonContentOf("kg/views/sparql-view.json")
      deltaClient.put[Json](s"/views/$project1/test-resource:cell-view2", payload, ScoobyDoo) { expectCreated }
    }

    "update a new SPARQL view" in {
      val payload = jsonContentOf("kg/views/sparql-view.json").mapObject(
        _.remove("resourceTag").remove("resourceTypes").remove("resourceSchemas")
      )
      deltaClient.put[Json](s"/views/$project1/test-resource:cell-view2?rev=1", payload, ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "restart the view indexing" in eventually {
      val expected =
        json"""{ "@context" : "https://bluebrain.github.io/nexus/contexts/offset.json", "@type" : "Start" }"""
      deltaClient.delete[Json](s"/views/$project1/test-resource:cell-view/offset", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual expected
      }
    }

    "reindex a resource after view undeprecation" in {
      givenADeprecatedView { view =>
        postResource { resource =>
          undeprecate(view) >> eventually { assertMatchId(view, resource) }
        }
      }
    }

    def givenAView(test: String => IO[Assertion]): IO[Assertion] = {
      val viewId      = genId()
      val viewPayload = jsonContentOf("kg/views/sparql-view-index-all.json", "withTag" -> false)
      val createView  = deltaClient.put[Json](s"/views/$project1/$viewId", viewPayload, ScoobyDoo) { expectCreated }

      createView >> test(viewId)
    }

    def givenADeprecatedView(test: String => IO[Assertion]): IO[Assertion] =
      givenAView { view =>
        val deprecateView = deltaClient.delete[Json](s"/views/$project1/$view?rev=1", ScoobyDoo) { expectOk }
        deprecateView >> test(view)
      }

    def undeprecate(view: String, rev: Int = 2): IO[Assertion] =
      deltaClient.putEmptyBody[Json](s"/views/$project1/$view/undeprecate?rev=$rev", ScoobyDoo) { expectOk }

    def postResource(test: String => IO[Assertion]): IO[Assertion] = {
      val id = genId()
      deltaClient.post[Json](s"/resources/$project1/_?indexing=sync", json"""{"@id": "$idBase$id"}""", ScoobyDoo) {
        expectCreated
      } >> test(id)
    }

    def assertMatchId(view: String, id: String): IO[Assertion] = {
      val query =
        s"""
           |SELECT (COUNT(*) as ?count)
           |WHERE { <$idBase$id> ?p ?o }
      """.stripMargin

      deltaClient.sparqlQuery[Json](s"/views/$project1/$view/sparql", query, ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        sparql.countResult(json).value should be > 0
      }
    }
  }
}
