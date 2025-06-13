package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.views.ScoobyDoo
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.iam.types.Permission.{Organizations, Views}
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import cats.implicits.*
import io.circe.{ACursor, Json}
import org.scalatest.Assertion

class ElasticSearchViewsSpec extends BaseIntegrationSpec {

  private val orgId  = genId()
  private val projId = genId()
  val project1       = s"$orgId/$projId"

  private val projId2 = genId()
  val project2        = s"$orgId/$projId2"

  private val projects = List(project1, project2)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- aclDsl.addPermission("/", ScoobyDoo, Organizations.Create)
      _ <- aclDsl.addPermissionAnonymous(s"/$project2", Views.Query)
      _ <- adminDsl.createOrganization(orgId, orgId, ScoobyDoo)
      _ <- adminDsl.createProjectWithName(orgId, projId, name = project1, ScoobyDoo)
      _ <- adminDsl.createProjectWithName(orgId, projId2, name = project2, ScoobyDoo)
    } yield succeed

    setup.accepted
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
      projects.parTraverse { project =>
        deltaClient.put[Json](
          s"/resources/$project/resource/test-resource:context",
          jsonContentOf("kg/views/context.json"),
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "create elasticsearch views with legacy fields and its pipeline equivalent" in {
      List(project1 -> "kg/views/elasticsearch/legacy-fields.json", project2 -> "kg/views/elasticsearch/pipeline.json")
        .parTraverse { case (project, file) =>
          deltaClient
            .put[Json](s"/views/$project/test-resource:cell-view", jsonContentOf(file, "withTag" -> false), ScoobyDoo) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
        }
    }

    "create elasticsearch views filtering on tag with legacy fields and its pipeline equivalent" in {
      List(project1 -> "kg/views/elasticsearch/legacy-fields.json", project2 -> "kg/views/elasticsearch/pipeline.json")
        .parTraverse { case (project, file) =>
          deltaClient.put[Json](
            s"/views/$project/test-resource:cell-view-tagged",
            jsonContentOf(file, "withTag" -> true),
            ScoobyDoo
          ) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
        }
    }

    "fail to create a view with an invalid mapping" in {
      val invalidMapping            =
        json"""{"mapping": "fail"}"""
      val payloadWithInvalidMapping = json"""{ "@type": "ElasticSearchView", "mapping": $invalidMapping }"""
      deltaClient.put[Json](s"/views/$project1/invalid", payloadWithInvalidMapping, ScoobyDoo) { expectBadRequest }
    }

    "fail to create a view with invalid settings" in {
      val invalidSettings            =
        json"""{"analysis": "fail"}"""
      val payloadWithInvalidSettings =
        json"""{ "@type": "ElasticSearchView", "mapping": { }, "settings": $invalidSettings }"""
      deltaClient.put[Json](s"/views/$project1/invalid", payloadWithInvalidSettings, ScoobyDoo) { expectBadRequest }
    }

    "create people view in project 2" in {
      deltaClient.put[Json](
        s"/views/$project2/test-resource:people",
        jsonContentOf("kg/views/elasticsearch/people-view.json"),
        ScoobyDoo
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "get the created elasticsearch views" in {
      val id = "https://dev.nexus.test.com/simplified-resource/cell-view"
      projects.parTraverse { project =>
        deltaClient.get[Json](s"/views/$project/test-resource:cell-view", ScoobyDoo) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = jsonContentOf(
            "kg/views/elasticsearch/indexing-response.json",
            replacements(
              ScoobyDoo,
              "id"      -> id,
              "self"    -> viewSelf(project, id),
              "project" -> project
            )*
          )

          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "create an AggregateElasticSearchView" in {
      elasticsearchViewsDsl.aggregate(
        "test-resource:agg-cell-view",
        project2,
        ScoobyDoo,
        project1 -> "https://dev.nexus.test.com/simplified-resource/cell-view",
        project2 -> "https://dev.nexus.test.com/simplified-resource/cell-view"
      )
    }

    "get the created AggregateElasticSearchView" in {
      val id = "https://dev.nexus.test.com/simplified-resource/agg-cell-view"
      deltaClient.get[Json](s"/views/$project2/test-resource:agg-cell-view", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK

        val expected = jsonContentOf(
          "kg/views/elasticsearch/aggregate-response.json",
          replacements(
            ScoobyDoo,
            "id"       -> id,
            "self"     -> viewSelf(project2, id),
            "project1" -> project1,
            "project2" -> project2
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
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "post instance without id" in {
      val payload = jsonContentOf(s"kg/views/instances/instance9.json")
      deltaClient.post[Json](
        s"/resources/$project2/resource?indexing=sync",
        payload,
        ScoobyDoo
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "wait until all instances are indexed in default view of project 2" in eventually {
      deltaClient.get[Json](s"/resources/$project2/resource", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        _total.getOption(json).value shouldEqual 5
      }
    }

    val invalidElasticQuery = json"""{ "query": { "other": {} } }"""

    "return 400 with bad query instances on main and custom views" in {
      for {
        _ <-
          deltaClient.post[Json](s"/views/$project1/test-resource:cell-view/_search", invalidElasticQuery, ScoobyDoo) {
            (json, response) =>
              response.status shouldEqual StatusCodes.BadRequest
              json shouldEqual jsonContentOf("kg/views/elasticsearch/elastic-error.json")
          }
        _ <- deltaClient.post[Json](s"/views/$project1/documents/_search", invalidElasticQuery, ScoobyDoo) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
                 json shouldEqual jsonContentOf("kg/views/elasticsearch/elastic-error.json")
             }
      } yield succeed
    }

    val sort             = json"""{ "sort": [{ "name.raw": { "order": "asc" } }] }"""
    val sortedMatchCells = json"""{ "query": { "term": { "@type": "Cell" } } }""" deepMerge sort
    val matchAll         = json"""{ "query": { "match_all": {} } }""" deepMerge sort

    "search instances on project 1 in cell-view" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("kg/views/elasticsearch/search-response.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$project1/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .unsafeRunSync()
      }
    }

    "get no instance in cell-view-tagged in project1 as nothing is tagged yet" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          totalHits.getOption(json).value shouldEqual 0
      }
    }

    "search cell instances on project 2" in eventually {
      deltaClient.post[Json](s"/views/$project2/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("kg/views/elasticsearch/search-response-2.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$project2/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .unsafeRunSync()
      }
    }

    "the person resource created with no id in payload should have the default id in _source" in eventually {
      deltaClient.post[Json](s"/views/$project2/test-resource:people/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val id       = hits(0)._id.string.getOption(json)
          val sourceId = hits(0)._source.`@id`.string.getOption(json)
          sourceId shouldEqual id
      }
    }

    "get no instance is indexed in cell-view-tagged in project2 as nothing is tagged yet" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          totalHits.getOption(json).value shouldEqual 0
      }
    }

    "search instances on project AggregatedElasticSearchView when logged" in eventually {
      deltaClient.post[Json](
        s"/views/$project2/test-resource:agg-cell-view/_search",
        sortedMatchCells,
        ScoobyDoo
      ) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val indexes   = hits.each._index.string.getAll(json)
        val toReplace = indexes.zipWithIndex.map { case (value, i) => s"index${i + 1}" -> value }
        filterKey("took")(json) shouldEqual
          jsonContentOf("kg/views/elasticsearch/search-response-aggregated.json", toReplace*)
      }
    }

    "search instances on project AggregatedElasticSearchView as anonymous" in eventually {
      deltaClient.post[Json](s"/views/$project2/test-resource:agg-cell-view/_search", sortedMatchCells, Anonymous) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("kg/views/elasticsearch/search-response-2.json", "index" -> index)
      }
    }

    "fetch statistics for cell-view" in eventually {
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

    "fetch statistics for cell-view-tagged" in eventually {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view-tagged/statistics", ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = filterNestedKeys("delayInSeconds")(
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

    "tag resources" in {
      (1 to 5).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        deltaClient.post[Json](
          s"/resources/$project1/resource/patchedcell:$unprefixedId/tags?rev=1",
          Json.obj("rev" -> Json.fromInt(1), "tag" -> Json.fromString("one")),
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "get newly tagged instances in cell-view-tagged in project1" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val total = totalHits.getOption(json).value
          total shouldEqual 5
      }
    }

    "get updated statistics for cell-view-tagged" in eventually {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view-tagged/statistics", ScoobyDoo) {
        (json, response) =>
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

    "remove @type on a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("kg/views/instances/instance1.json"))
      val id           = `@id`.getOption(payload).value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")

      deltaClient.put[Json](
        s"/resources/$project1/_/patchedcell:$unprefixedId?rev=2",
        filterKey("@id")(payload),
        ScoobyDoo
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "search instances on project 1 after removed @type" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("kg/views/elasticsearch/search-response-no-type.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$project1/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .unsafeRunSync()
      }
    }

    "deprecate a resource" in {
      val payload      = filterKey("@type")(jsonContentOf("kg/views/instances/instance2.json"))
      val id           = payload.asObject.value("@id").value.asString.value
      val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
      deltaClient.delete[Json](s"/resources/$project1/_/patchedcell:$unprefixedId?rev=2", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "search instances on project 1 after deprecated" in eventually {
      deltaClient.post[Json](s"/views/$project1/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, result) =>
          result.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("kg/views/elasticsearch/search-response-no-deprecated.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$project1/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .unsafeRunSync()
      }
    }

    "restart the view indexing" in eventually {
      deltaClient.delete[Json](s"/views/$project1/test-resource:cell-view/offset", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected =
          json"""{ "@context" : "https://bluebrain.github.io/nexus/contexts/offset.json", "@type" : "Start" }"""
        json shouldEqual expected
      }
    }

    "fail to fetch mapping without permission" in {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view/_mapping", Anonymous) { expectForbidden }
    }

    "fail to fetch mapping for view that doesn't exist" in {
      deltaClient.get[Json](s"/views/$project1/test-resource:wrong-view/_mapping", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.NotFound
        json shouldEqual jsonContentOf(
          "kg/views/elasticsearch/errors/es-view-not-found.json",
          replacements(
            ScoobyDoo,
            "viewId"     -> "https://dev.nexus.test.com/simplified-resource/wrong-view",
            "projectRef" -> project1
          )*
        )
      }
    }

    "fail to fetch mapping for aggregate view" in {
      val view = "test-resource:agg-cell-view"
      deltaClient.get[Json](s"/views/$project2/$view/_mapping", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf(
          "kg/views/elasticsearch/errors/es-incorrect-view-type.json",
          replacements(
            ScoobyDoo,
            "view"         -> view,
            "providedType" -> "AggregateElasticSearchView",
            "expectedType" -> "ElasticSearchView"
          )*
        )
      }
    }

    "return the view's mapping" in {
      deltaClient.get[Json](s"/views/$project1/test-resource:cell-view/_mapping", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK

        def hasOnlyOneKey = (j: ACursor) => j.keys.exists(_.size == 1)
        def downFirstKey  = (j: ACursor) => j.downField(j.keys.get.head)

        assert(hasOnlyOneKey(json.hcursor))
        val firstKey = downFirstKey(json.hcursor)
        assert(hasOnlyOneKey(firstKey))
        assert(downFirstKey(firstKey).key.contains("mappings"))
      }
    }

    "undeprecate a deprecated view" in {
      givenADeprecatedView { view =>
        val assertUndeprecated = deltaClient.get[Json](s"/views/$project1/$view", ScoobyDoo) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.hcursor.get[Boolean]("_deprecated").toOption should contain(false)
        }
        undeprecate(view) >> assertUndeprecated
      }
    }

    "reindex a resource after a view is undeprecated" in {
      givenADeprecatedView { view =>
        givenAPersonResource { person =>
          undeprecate(view) >> eventually { assertMatchId(view, person) }
        }
      }
    }
  }

  def givenAView(test: String => IO[Assertion]): IO[Assertion] = {
    val viewId      = genId()
    val viewPayload = jsonContentOf("kg/views/elasticsearch/people-view.json", "withTag" -> false)
    val createView  = deltaClient.put[Json](s"/views/$project1/$viewId", viewPayload, ScoobyDoo) { expectCreated }

    createView >> test(viewId)
  }

  def givenADeprecatedView(test: String => IO[Assertion]): IO[Assertion] =
    givenAView { view =>
      val deprecateView = deltaClient.delete[Json](s"/views/$project1/$view?rev=1", ScoobyDoo) { expectOk }
      deprecateView >> test(view)
    }

  def givenAPersonResource(test: String => IO[Assertion]): IO[Assertion] = {
    val id = genId()
    deltaClient.put[Json](
      s"/resources/$project1/_/$id?indexing=sync",
      jsonContentOf("kg/resources/person.json"),
      ScoobyDoo
    ) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    } >> test(id)
  }

  def undeprecate(view: String, rev: Int = 2): IO[Assertion] =
    deltaClient.putEmptyBody[Json](s"/views/$project1/$view/undeprecate?rev=$rev", ScoobyDoo) { expectOk }

  def assertMatchId(view: String, id: String): IO[Assertion] =
    deltaClient
      .post[Json](
        s"/views/$project1/$view/_search",
        json"""{ "query": { "match": { "@id": "$id" } } }""",
        ScoobyDoo
      ) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        totalHits.getOption(json).value shouldEqual 1
      }
}
