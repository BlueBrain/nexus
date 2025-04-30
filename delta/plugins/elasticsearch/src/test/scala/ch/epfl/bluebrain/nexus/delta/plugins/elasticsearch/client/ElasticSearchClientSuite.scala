package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.BulkResponse.MixedOutcomes.Outcome
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.Refresh.WaitFor
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError.{ElasticsearchActionError, ElasticsearchCreateIndexError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.ScoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.ScoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Json, JsonObject}
import munit.AnyFixture
import org.http4s.Query

import scala.concurrent.duration.*

class ElasticSearchClientSuite extends NexusSuite with ElasticSearchClientSetup.Fixture with CirceLiteral {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private val page = FromPagination(0, 100)

  override def munitFixtures: Seq[AnyFixture[?]] = List(esClient)

  private lazy val client = esClient()

  private def searchAllIn(index: IndexLabel): IO[Seq[JsonObject]] =
    client.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.empty).map(_.sources)

  private def replaceAndRefresh(index: IndexLabel, id: String, document: JsonObject) =
    client.replace(index, id, document) >> client.refresh(index)

  private def generateIndexLabel = IndexLabel.unsafe(genString())

  test("Fetch the service description") {
    client.serviceDescription.assertEquals(ServiceDescription("elasticsearch", ElasticSearchContainer.Version))
  }

  test("Verify that an index does not exist then create it") {
    val index = generateIndexLabel
    for {
      _ <- client.existsIndex(index).assertEquals(false)
      _ <- client.createIndex(index).assertEquals(true)
      _ <- client.existsIndex(index).assertEquals(true)
      _ <- client.createIndex(index, JsonObject.empty).assertEquals(false)
    } yield ()
  }

  test("Fail to create an index with wrong payload") {
    val index = generateIndexLabel
    client.createIndex(index, jobj"""{"key": "value"}""").intercept[ElasticsearchCreateIndexError]
  }

  test("Delete an index") {
    val index = generateIndexLabel
    for {
      settings <- loader.jsonObjectContentOf("defaults/default-settings.json", "number_of_shards" -> 1)
      mapping  <- loader.jsonObjectContentOf("defaults/default-mapping.json")
      _        <- client.createIndex(index, Some(mapping), Some(settings)).assertEquals(true)
      _        <- client.deleteIndex(index).assertEquals(true)
      _        <- client.deleteIndex(index).assertEquals(false)
    } yield ()
  }

  test("Attempt to delete a non-existing index") {
    client.deleteIndex(generateIndexLabel).assertEquals(false)
  }

  test("Replace a document") {
    val index           = generateIndexLabel
    val document        = jobj"""{"key": "value"}"""
    val documentUpdated = jobj"""{"key": "value2"}"""
    for {
      _ <- client.createIndex(index)
      _ <- replaceAndRefresh(index, "1", document)
      _ <- searchAllIn(index).assertEquals(Vector(document))
      _ <- replaceAndRefresh(index, "1", documentUpdated)
      _ <- searchAllIn(index).assertEquals(Vector(documentUpdated))
    } yield ()
  }

  test("Run bulk operations") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", Some("routing"), json"""{ "field1" : "value1" }"""),
      ElasticSearchAction.Delete(index, "2", Some("routing2")),
      ElasticSearchAction.Index(index, "2", Some("routing2"), json"""{ "field1" : "value1" }"""),
      ElasticSearchAction.Delete(index, "2", Some("routing2")),
      ElasticSearchAction.Create(index, "3", Some("routing"), json"""{ "field1" : "value3" }"""),
      ElasticSearchAction.Update(index, "1", Some("routing"), json"""{ "doc" : {"field2" : "value2"} }""")
    )

    val expectedDocuments = Vector(jobj"""{"field1": "value3"}""", jobj"""{"field1": "value1", "field2" : "value2"}""")

    client.bulk(operations, WaitFor).assertEquals(BulkResponse.Success) >>
      searchAllIn(index).assertEquals(expectedDocuments)
  }

  test("Run bulk operations with errors") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "field1" : "value1" }"""),
      ElasticSearchAction.Delete(index, "2", None),
      ElasticSearchAction.Index(index, "2", None, json"""{ "field1" : 27 }"""),
      ElasticSearchAction.Delete(index, "3", None),
      ElasticSearchAction.Create(index, "3", None, json"""{ "field1" : "value3" }"""),
      ElasticSearchAction.Update(index, "5", None, json"""{ "doc" : {"field2" : "value2"} }""")
    )

    client.bulk(operations).map {
      case BulkResponse.Success              => fail("errors expected")
      case BulkResponse.MixedOutcomes(items) =>
        val (failures, successes) = items.partitionMap { case (key, outcome) =>
          Either.cond(outcome == Outcome.Success(key), key, key)
        }
        assertEquals(successes, List("1", "2", "3"))
        assertEquals(failures, List("5"))
    }
  }

  test("Get the source of the given document") {
    val index = generateIndexLabel
    val doc   = jobj"""{ "field1" : 1 }"""

    replaceAndRefresh(index, "1", doc) >>
      client.getSource[Json](index, "1").assertEquals(Some(doc.asJson)) >>
      client.getSource[Json](index, "x").assertEquals(None)
  }

  test("Count") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "field1" : 1 }"""),
      ElasticSearchAction.Index(index, "2", None, json"""{ "field1" : 2 }"""),
      ElasticSearchAction.Index(index, "3", None, json"""{ "doc" : {"field2" : 4} }""")
    )
    client.bulk(operations, Refresh.WaitFor) >>
      client.count(index.value).assertEquals(3L)
  }

  test("Search") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "field1" : 1 }"""),
      ElasticSearchAction.Create(index, "3", None, json"""{ "field1" : 3 }"""),
      ElasticSearchAction.Update(index, "1", None, json"""{ "doc" : {"field2" : "value2"} }""")
    )

    for {
      _        <- client.bulk(operations, Refresh.WaitFor)
      query     = QueryBuilder(jobj"""{"query": {"bool": {"must": {"exists": {"field": "field1"} } } } }""")
                    .withPage(page)
                    .withSort(SortList(List(Sort("-field1"))))
      expected  = SearchResults(2, Vector(jobj"""{ "field1" : 3 }""", jobj"""{ "field1" : 1, "field2" : "value2"}"""))
                    .copy(token = Some("[1]"))
      _        <- client.search(query, Set(index.value), Query.empty).assertEquals(expected)
      query2    = QueryBuilder(jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }""").withPage(page)
      expected2 = ScoredSearchResults(1, 1f, Vector(ScoredResultEntry(1f, jobj"""{ "field1" : 3 }""")))
      _        <- client.search(query2, Set(index.value), Query.empty).assertEquals(expected2)
    } yield ()
  }

  test("Search returning raw results") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "field1" : 1 }"""),
      ElasticSearchAction.Create(index, "3", None, json"""{ "field1" : 3 }"""),
      ElasticSearchAction.Update(index, "1", None, json"""{ "doc" : {"field2" : "value2"} }""")
    )

    for {
      _               <- client.bulk(operations, Refresh.WaitFor)
      query            = jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }"""
      expectedResults <- loader
                           .jsonContentOf("elasticsearch-results.json", "index" -> index)
      _               <- client
                           .search(query, Set(index.value), Query.empty)(SortList.empty)
                           .map(_.removeKeys("took"))
                           .assertEquals(expectedResults)
    } yield ()
  }

  test("Delete documents by query") {
    val index      = generateIndexLabel
    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "field1" : 1 }"""),
      ElasticSearchAction.Create(index, "2", None, json"""{ "field1" : 3 }""")
    )

    for {
      // Indexing and checking count
      _    <- client.bulk(operations, Refresh.WaitFor)
      _    <- client.count(index.value).assertEquals(2L)
      // Deleting document matching the given query
      query = jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }"""
      _    <- client.deleteByQuery(query, index)
      _    <- client.count(index.value).assertEquals(1L).eventually
      _    <- client.getSource[Json](index, "1")
      _    <- client.getSource[Json](index, "2").assertEquals(None)
    } yield ()
  }

  test("Create a point in time for the given index") {
    val index = generateIndexLabel
    for {
      _   <- client.createIndex(index)
      pit <- client.createPointInTime(index, 30.seconds)
      _   <- client.deletePointInTime(pit)
    } yield ()
  }

  test("Create an alias for an index and then delete it") {
    val index      = generateIndexLabel
    val alias      = generateIndexLabel
    val indexAlias = IndexAlias(index, alias, Some("routing"), Some(JsonObject("match_all" := Json.obj())))
    for {
      _ <- client.createIndex(index)
      _ <- client.createAlias(indexAlias)
      _ <- client.existsIndex(alias).assertEquals(true)
      _ <- client.removeAlias(index, alias)
      _ <- client.existsIndex(alias).assertEquals(false)
      _ <- client.existsIndex(index).assertEquals(true)
    } yield ()
  }

  test("Create an alias for a non-existing index should fail") {
    val index      = generateIndexLabel
    val alias      = generateIndexLabel
    val indexAlias = IndexAlias(index, alias, Some("routing"), Some(JsonObject("match_all" := Json.obj())))
    client.createAlias(indexAlias).intercept[ElasticsearchActionError]
  }

}
