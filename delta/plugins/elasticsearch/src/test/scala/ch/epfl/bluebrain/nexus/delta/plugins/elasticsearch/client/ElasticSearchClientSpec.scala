package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.ScoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.ScoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.JsonObject
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchClientSpec
    extends TestKit(ActorSystem("ElasticSearchClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with ElasticSearchClientSetup
    with ConfigFixtures
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with IOValues
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  private val page = FromPagination(0, 100)

  private def searchAllIn(index: IndexLabel): Seq[JsonObject] =
    esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted.sources

  "An ElasticSearch Client" should {

    "fetch the service description" in {
      esClient.serviceDescription.accepted shouldEqual ServiceDescription(Name.unsafe("elasticsearch"), "7.13.4")
    }

    "verify that an index does not exist" in {
      esClient.existsIndex(IndexLabel("some").rightValue).accepted shouldEqual false
    }

    "create index" in {
      esClient.createIndex(IndexLabel("some").rightValue).accepted shouldEqual true
    }

    "attempt to create index a second time" in {
      esClient.createIndex(IndexLabel("some").rightValue).accepted shouldEqual false
    }

    "attempt to create an index with wrong payload" in {
      esClient
        .createIndex(IndexLabel("other").rightValue, jobj"""{"key": "value"}""")
        .rejectedWith[HttpClientStatusError]
    }

    "delete an index" in {
      val settings = jsonObjectContentOf("defaults/default-settings.json")
      val mappings = jsonObjectContentOf("defaults/default-mapping.json")
      esClient.createIndex(IndexLabel("other").rightValue, Some(mappings), Some(settings)).accepted
      esClient.deleteIndex(IndexLabel("other").rightValue).accepted shouldEqual true
    }

    "attempt to delete a non existing index" in {
      esClient.deleteIndex(IndexLabel("other").rightValue).accepted shouldEqual false
    }

    "replace a document" in {
      val index           = IndexLabel(genString()).rightValue
      val document        = jobj"""{"key": "value"}"""
      val documentUpdated = jobj"""{"key": "value2"}"""
      esClient.createIndex(index).accepted
      esClient.replace(index, "1", document).accepted
      eventually(searchAllIn(index) shouldEqual Vector(document))
      esClient.replace(index, "1", documentUpdated).accepted
      eventually(searchAllIn(index) shouldEqual Vector(documentUpdated))
    }

    "delete a document" in {
      val index    = IndexLabel(genString()).rightValue
      val document = jobj"""{"key": "value"}"""
      esClient.createIndex(index).accepted
      esClient.replace(index, "1", document).accepted
      eventually(searchAllIn(index) shouldEqual Vector(document))
      esClient.delete(index, "1").accepted shouldEqual true
      esClient.delete(index, "1").accepted shouldEqual false
      eventually(searchAllIn(index) shouldEqual Vector.empty[JsonObject])
    }

    "run bulk operation" in {
      val index      = IndexLabel(genString()).rightValue
      val operations = List(
        ElasticSearchBulk.Index(index, "1", json"""{ "field1" : "value1" }"""),
        ElasticSearchBulk.Delete(index, "2"),
        ElasticSearchBulk.Index(index, "2", json"""{ "field1" : "value1" }"""),
        ElasticSearchBulk.Delete(index, "2"),
        ElasticSearchBulk.Create(index, "3", json"""{ "field1" : "value3" }"""),
        ElasticSearchBulk.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
      )
      esClient.bulk(operations).accepted
      eventually {
        searchAllIn(index) shouldEqual
          Vector(jobj"""{"field1": "value3"}""", jobj"""{"field1": "value1", "field2" : "value2"}""")
      }
    }

    "search" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchBulk.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchBulk.Create(index, "3", json"""{ "field1" : 3 }"""),
        ElasticSearchBulk.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
      )
      esClient.bulk(operations, Refresh.WaitFor).accepted
      val query      = QueryBuilder(jobj"""{"query": {"bool": {"must": {"exists": {"field": "field1"} } } } }""")
        .withPage(page)
        .withSort(SortList(List(Sort("-field1"))))
      esClient.search(query, Set(index.value), Query.Empty).accepted shouldEqual
        SearchResults(2, Vector(jobj"""{ "field1" : 3 }""", jobj"""{ "field1" : 1, "field2" : "value2"}"""))
          .copy(token = Some("[1]"))

      val query2 = QueryBuilder(jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }""").withPage(page)
      esClient.search(query2, Set(index.value), Query.Empty).accepted shouldEqual
        ScoredSearchResults(1, 1f, Vector(ScoredResultEntry(1f, jobj"""{ "field1" : 3 }""")))
    }

    "search returning raw results" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchBulk.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchBulk.Create(index, "3", json"""{ "field1" : 3 }"""),
        ElasticSearchBulk.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
      )
      esClient.bulk(operations).accepted
      val query2     = jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }"""
      eventually {
        esClient
          .search(query2, Set(index.value), Query.Empty)(SortList.empty)
          .accepted
          .removeKeys("took") shouldEqual
          jsonContentOf("elasticsearch-results.json", "index" -> index)
      }
    }
  }
}
