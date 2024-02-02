package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ScalaTestElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse.MixedOutcomes.Outcome
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.{BulkResponse, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.ScoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.ScoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.{Json, JsonObject}
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchClientSpec(override val docker: ElasticSearchDocker)
    extends TestKit(ActorSystem("ElasticSearchClientSpec"))
    with CatsEffectSpec
    with ScalaTestElasticSearchClientSetup
    with CirceLiteral
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)
  implicit val baseUri: BaseUri                        = BaseUri("http://localhost", Label.unsafe("v1"))

  private val page = FromPagination(0, 100)

  private def searchAllIn(index: IndexLabel): Seq[JsonObject] =
    esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted.sources

  "An ElasticSearch Client" should {

    "fetch the service description" in {
      esClient.serviceDescription.accepted shouldEqual ServiceDescription(
        Name.unsafe("elasticsearch"),
        docker.version
      )
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
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : "value1" }"""),
        ElasticSearchAction.Delete(index, "2"),
        ElasticSearchAction.Index(index, "2", json"""{ "field1" : "value1" }"""),
        ElasticSearchAction.Delete(index, "2"),
        ElasticSearchAction.Create(index, "3", json"""{ "field1" : "value3" }"""),
        ElasticSearchAction.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
      )
      esClient.bulk(operations).accepted
      eventually {
        searchAllIn(index) shouldEqual
          Vector(jobj"""{"field1": "value3"}""", jobj"""{"field1": "value1", "field2" : "value2"}""")
      }
    }

    "run bulk operation with errors" in {
      val index      = IndexLabel(genString()).rightValue
      val operations = List(
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : "value1" }"""),
        ElasticSearchAction.Delete(index, "2"),
        ElasticSearchAction.Index(index, "2", json"""{ "field1" : 27 }"""),
        ElasticSearchAction.Delete(index, "3"),
        ElasticSearchAction.Create(index, "3", json"""{ "field1" : "value3" }"""),
        ElasticSearchAction.Update(index, "5", json"""{ "doc" : {"field2" : "value2"} }""")
      )
      val result     = esClient.bulk(operations).accepted
      result match {
        case BulkResponse.Success              => fail("errors expected")
        case BulkResponse.MixedOutcomes(items) => {
          items.size shouldEqual 4
          items.get("1").value shouldEqual Outcome.Success
          items.get("2").value shouldEqual Outcome.Success
          items.get("3").value shouldEqual Outcome.Success
          items.get("5").value shouldBe an[Outcome.Error]
        }
      }
    }

    "get the source of the given document" in {
      val index = IndexLabel(genString()).rightValue
      val doc   = json"""{ "field1" : 1 }"""

      val operations = List(ElasticSearchAction.Index(index, "1", doc))
      esClient.bulk(operations, Refresh.WaitFor).accepted

      esClient.getSource[Json](index, "1").accepted shouldEqual doc
      esClient.getSource[Json](index, "2").rejectedWith[HttpClientStatusError]
    }

    "perform the multiget query" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchAction.Index(index, "2", json"""{ "field1" : 2 }"""),
        ElasticSearchAction.Index(index, "3", json"""{ "doc" : {"field2" : 4} }""")
      )
      esClient.bulk(operations, Refresh.WaitFor).accepted

      esClient.multiGet[Int](index, Set("1", "2", "3", "4"), "field1").accepted shouldEqual Map(
        "1" -> Some(1),
        "2" -> Some(2),
        "3" -> None
      )
    }

    "count" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchAction.Index(index, "2", json"""{ "field1" : 2 }"""),
        ElasticSearchAction.Index(index, "3", json"""{ "doc" : {"field2" : 4} }""")
      )
      esClient.bulk(operations, Refresh.WaitFor).accepted

      esClient.count(index.value).accepted shouldEqual 3L
    }

    "search" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchAction.Create(index, "3", json"""{ "field1" : 3 }"""),
        ElasticSearchAction.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
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
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchAction.Create(index, "3", json"""{ "field1" : 3 }"""),
        ElasticSearchAction.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
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

    "aggregate" in {
      val index       = IndexLabel(genString()).rightValue
      val operations  = List(
        ElasticSearchAction.Index(index, "1", json"""{ "_project": "proj1", "@type" : "Person" }"""),
        ElasticSearchAction.Index(index, "2", json"""{ "_project": "proj2", "@type" : "Person" }"""),
        ElasticSearchAction.Index(index, "3", json"""{ "_project": "proj3", "@type" : "Dog" }""")
      )
      val params      = ResourcesSearchParams()
      val expectedAgg = jsonContentOf("elasticsearch-agg-results.json").asObject.get

      val mapping =
        json"""{ "properties": {
               "@type": { "type": "keyword" },
               "_project": { "type": "keyword" } } }""".asObject

      val aggregate = for {
        _   <- esClient.createIndex(index, mapping, None)
        _   <- esClient.bulk(operations)
        agg <- esClient.aggregate(params, Set(index.value), Query.Empty, 100)
      } yield agg

      eventually {
        aggregate.accepted shouldEqual AggregationResult(3, expectedAgg)
      }
    }

    "delete documents by" in {
      val index = IndexLabel(genString()).rightValue

      val operations = List(
        ElasticSearchAction.Index(index, "1", json"""{ "field1" : 1 }"""),
        ElasticSearchAction.Create(index, "2", json"""{ "field1" : 3 }"""),
        ElasticSearchAction.Update(index, "1", json"""{ "doc" : {"field2" : "value2"} }""")
      )

      {
        for {
          // Indexing and checking count
          _        <- esClient.bulk(operations)
          _        <- esClient.refresh(index)
          original <- esClient.count(index.value)
          _         = original shouldEqual 2L
          // Deleting document matching the given query
          query     = jobj"""{"query": {"bool": {"must": {"term": {"field1": 3} } } } }"""
          _        <- esClient.deleteByQuery(query, index)
          // Checking docs again
          newCount <- esClient.count(index.value)
          _         = newCount shouldEqual 1L
          doc1     <- esClient.getSource[Json](index, "1").attemptNarrow[HttpClientError]
          _         = doc1.rightValue
          doc2     <- esClient.getSource[Json](index, "2").attemptNarrow[HttpClientError]
          _         = doc2.leftValue.errorCode.value shouldEqual StatusCodes.NotFound
        } yield ()
      }.accepted
    }
  }
}
