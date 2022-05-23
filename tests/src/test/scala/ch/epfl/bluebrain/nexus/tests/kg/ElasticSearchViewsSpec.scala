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

class ElasticSearchViewsSpec extends BaseSpec with EitherValuable with CirceEq {

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
      projects.parTraverse { project =>
        deltaClient.put[Json](
          s"/resources/$project/resource/test-resource:context",
          jsonContentOf("/kg/views/context.json"),
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "create elasticsearch views with legacy fields and its pipeline equivalent" in {
      List(fullId -> "/kg/views/elasticsearch/legacy-fields.json", fullId2 -> "/kg/views/elasticsearch/pipeline.json")
        .parTraverse { case (project, file) =>
          deltaClient
            .put[Json](s"/views/$project/test-resource:cell-view", jsonContentOf(file, "withTag" -> false), ScoobyDoo) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
        }
    }

    "create elasticsearch views filtering on tag with legacy fields and its pipeline equivalent" in {
      List(fullId -> "/kg/views/elasticsearch/legacy-fields.json", fullId2 -> "/kg/views/elasticsearch/pipeline.json")
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

    "get the created elasticsearch views" in {
      projects.parTraverse { project =>
        deltaClient.get[Json](s"/views/$project/test-resource:cell-view", ScoobyDoo) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = jsonContentOf(
            "/kg/views/elasticsearch/indexing-response.json",
            replacements(
              ScoobyDoo,
              "id"             -> "https://dev.nexus.test.com/simplified-resource/cell-view",
              "self"           -> s"${config.deltaUri}/views/$project/test-resource:cell-view",
              "project-parent" -> s"${config.deltaUri}/projects/$project",
              "project"        -> project
            ): _*
          )

          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "create an AggregateElasticSearchView" in {
      elasticsearchViewsDsl.aggregate(
        "test-resource:agg-cell-view",
        fullId2,
        ScoobyDoo,
        fullId  -> "https://dev.nexus.test.com/simplified-resource/cell-view",
        fullId2 -> "https://dev.nexus.test.com/simplified-resource/cell-view"
      )
    }

    "get the created AggregateElasticSearchView" in {
      deltaClient.get[Json](s"/views/$fullId2/test-resource:agg-cell-view", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK

        val expected = jsonContentOf(
          "/kg/views/elasticsearch/aggregate-response.json",
          replacements(
            ScoobyDoo,
            "id"             -> "https://dev.nexus.test.com/simplified-resource/agg-cell-view",
            "resources"      -> s"${config.deltaUri}/views/$fullId2/test-resource:agg-cell-view",
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

    "wait until in project view is indexed" in eventually {
      deltaClient.get[Json](s"/views/$fullId", ScoobyDoo) { (json, response) =>
        _total.getOption(json).value shouldEqual 5
        response.status shouldEqual StatusCodes.OK
      }
    }

    "wait until all instances are indexed in default view of project 2" in eventually {
      deltaClient.get[Json](s"/resources/$fullId2/resource", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        _total.getOption(json).value shouldEqual 4
      }
    }

    "return 400 with bad query instances" in {
      deltaClient.post[Json](
        s"/views/$fullId/test-resource:cell-view/_search",
        json"""{ "query": { "other": {} } }""",
        ScoobyDoo
      ) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("/kg/views/elasticsearch/elastic-error.json")
      }
    }

    val sort             = json"""{ "sort": [{ "name.raw": { "order": "asc" } }] }"""
    val sortedMatchCells = json"""{ "query": { "term": { "@type": "Cell" } } }""" deepMerge sort
    val matchAll         = json"""{ "query": { "match_all": {} } }""" deepMerge sort

    "search instances on project 1 in cell-view" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("/kg/views/elasticsearch/search-response.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$fullId/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .runSyncUnsafe()
      }
    }

    "get no instance in cell-view-tagged in project1 as nothing is tagged yet" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          totalHits.getOption(json).value shouldEqual 0
      }
    }

    "search instances on project 2" in eventually {
      deltaClient.post[Json](s"/views/$fullId2/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("/kg/views/elasticsearch/search-response-2.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$fullId2/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .runSyncUnsafe()
      }
    }

    "get no instance is indexed in cell-view-tagged in project2 as nothing is tagged yet" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          totalHits.getOption(json).value shouldEqual 0
      }
    }

    "search instances on project AggregatedElasticSearchView when logged" in eventually {
      deltaClient.post[Json](
        s"/views/$fullId2/test-resource:agg-cell-view/_search",
        sortedMatchCells,
        ScoobyDoo
      ) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val indexes   = hits.each._index.string.getAll(json)
        val toReplace = indexes.zipWithIndex.map { case (value, i) => s"index${i + 1}" -> value }
        filterKey("took")(json) shouldEqual
          jsonContentOf("/kg/views/elasticsearch/search-response-aggregated.json", toReplace: _*)
      }
    }

    "search instances on project AggregatedElasticSearchView as anonymous" in eventually {
      deltaClient.post[Json](s"/views/$fullId2/test-resource:agg-cell-view/_search", sortedMatchCells, Anonymous) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("/kg/views/elasticsearch/search-response-2.json", "index" -> index)
      }
    }

    "fetch statistics for cell-view" in eventually {
      deltaClient.get[Json](s"/views/$fullId/test-resource:cell-view/statistics", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "/kg/views/statistics.json",
          "total"     -> "14",
          "processed" -> "14",
          "evaluated" -> "5",
          "discarded" -> "9",
          "remaining" -> "0"
        )
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "fetch statistics for cell-view-tagged" in eventually {
      deltaClient.get[Json](s"/views/$fullId/test-resource:cell-view-tagged/statistics", ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = jsonContentOf(
            "/kg/views/statistics.json",
            "total"     -> "14",
            "processed" -> "14",
            "evaluated" -> "0",
            "discarded" -> "14",
            "remaining" -> "0"
          )
          filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "tag resources" in {
      (1 to 5).toList.parTraverse { i =>
        val payload      = jsonContentOf(s"/kg/views/instances/instance$i.json")
        val id           = `@id`.getOption(payload).value
        val unprefixedId = id.stripPrefix("https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/")
        deltaClient.post[Json](
          s"/resources/$fullId/resource/patchedcell:$unprefixedId/tags?rev=1",
          Json.obj("rev" -> Json.fromLong(1L), "tag" -> Json.fromString("one")),
          ScoobyDoo
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "get newly tagged instances in cell-view-tagged in project1" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view-tagged/_search", matchAll, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val total = totalHits.getOption(json).value
          total shouldEqual 5
      }
    }

    "get updated statistics for cell-view-tagged" in eventually {
      deltaClient.get[Json](s"/views/$fullId/test-resource:cell-view-tagged/statistics", ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = jsonContentOf(
            "/kg/views/statistics.json",
            "total"     -> "19",
            "processed" -> "19",
            "evaluated" -> "5",
            "discarded" -> "14",
            "remaining" -> "0"
          )
          filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
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

    "search instances on project 1 after removed @type" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("/kg/views/elasticsearch/search-response-no-type.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$fullId/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .runSyncUnsafe()
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

    "search instances on project 1 after deprecated" in eventually {
      deltaClient.post[Json](s"/views/$fullId/test-resource:cell-view/_search", sortedMatchCells, ScoobyDoo) {
        (json, result) =>
          result.status shouldEqual StatusCodes.OK
          val index = hits(0)._index.string.getOption(json).value
          filterKey("took")(json) shouldEqual
            jsonContentOf("/kg/views/elasticsearch/search-response-no-deprecated.json", "index" -> index)

          deltaClient
            .post[Json](s"/views/$fullId/test-resource:cell-view/_search", matchAll, ScoobyDoo) { (json2, _) =>
              filterKey("took")(json2) shouldEqual filterKey("took")(json)
            }
            .runSyncUnsafe()
      }
    }
  }
}
