package ch.epfl.bluebrain.nexus.commons.es.client

import java.util.regex.Pattern

import akka.http.scaladsl.model.StatusCodes
import cats.effect
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient.BulkOp
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.commons.es.server.embed.ElasticServer
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult._
import ch.epfl.bluebrain.nexus.commons.search.QueryResults._
import ch.epfl.bluebrain.nexus.commons.search.{Pagination, QueryResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{IOValues, Resources}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import org.scalatest.{Assertions, CancelAfterFailure, Inspectors, OptionValues}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ElasticSearchClientSpec
    extends ElasticServer
    with ScalaFutures
    with Matchers
    with Resources
    with Inspectors
    with CancelAfterFailure
    with Assertions
    with OptionValues
    with Eventually
    with IOValues {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(15.seconds, 300.milliseconds)

  private def genIndexString(): String =
    genString(length = 10, pool = Vector.range('a', 'f') ++ """ "\<>|,/?""")

  private implicit val uc: UntypedHttpClient[IO]        = untyped[IO]
  private implicit val timer: effect.Timer[IO]          = IO.timer(ec)
  private implicit val retryConfig: RetryStrategyConfig = RetryStrategyConfig("once", 100.millis, 0.millis, 0, 0.millis)

  "An ElasticSearchClient" when {
    val cl: ElasticSearchClient[IO] = ElasticSearchClient[IO](esUri)
    val indexPayload                = jsonContentOf("/commons/es/index_payload.json")
    val mappingPayload              = jsonContentOf("/commons/es/mapping_payload.json")
    def genJson(k: String, k2: String): Json =
      Json.obj(k -> Json.fromString(genString()), k2 -> Json.fromString(genString()))
    def getValue(key: String, json: Json): String = json.hcursor.get[String](key).getOrElse("")

    val matchAll = Json.obj("query" -> Json.obj("match_all" -> Json.obj()))

    "sanitazing index names" should {
      "succeed" in {
        forAll(""" "*\<>|,/?""") { ch =>
          cl.sanitize(s"a${ch}a", allowWildCard = false) shouldEqual "a_a"
        }
        cl.sanitize(s"a*a", allowWildCard = true) shouldEqual "a*a"
      }
    }

    "fetching service descrption" in {
      cl.serviceDescription.ioValue shouldEqual ServiceDescription("elasticsearch", "7.5.1")
    }

    "performing index operations" should {

      "return false when index does not exist" in {
        cl.existsIndex("some").ioValue shouldEqual false
      }

      "create mappings if index does not exist" in {
        val index = genIndexString()
        cl.createIndex(index, indexPayload).ioValue shouldEqual true
        cl.createIndex(index, indexPayload).ioValue shouldEqual false
      }

      "create mappings and index settings" in {
        val index = genIndexString()
        cl.createIndex(index, indexPayload).ioValue shouldEqual true
        cl.existsIndex(index).ioValue shouldEqual true
      }

      "create mappings" in {
        val index = genIndexString()
        cl.createIndex(index).ioValue shouldEqual true
        cl.updateMapping(index, mappingPayload).ioValue shouldEqual true
        cl.updateMapping(genIndexString(), mappingPayload).ioValue shouldEqual false
        cl.updateMapping(index, indexPayload).failed[ElasticSearchClientError]
      }

      "delete index" in {
        val index = genIndexString()
        cl.createIndex(index, indexPayload).ioValue shouldEqual true
        cl.deleteIndex(index).ioValue shouldEqual true
        cl.deleteIndex(index).ioValue shouldEqual false
      }
    }

    "performing document operations" should {
      val p: Pagination                                         = Pagination(0, 3)
      implicit val D: Decoder[QueryResults[Json]]               = ElasticSearchDecoder[Json]
      implicit val rsSearch: HttpClient[IO, QueryResults[Json]] = withUnmarshaller[IO, QueryResults[Json]]
      implicit val rsGet: HttpClient[IO, Json]                  = withUnmarshaller[IO, Json]

      val index          = genIndexString()
      val indexSanitized = cl.sanitize(index, allowWildCard = false)
      val list           = List.fill(10)(genString() -> genJson("key", "key2"))

      "add documents" in {
        cl.createIndex(index, indexPayload).ioValue shouldEqual true
        val ops = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
        cl.bulk(ops).ioValue shouldEqual (())
      }

      "override documents" in {
        val (id, json) = list(0)
        cl.create(indexSanitized, id, json, overrideIfExists = false).failed[Throwable]
        cl.create(indexSanitized, id, json).ioValue
      }

      "fetch documents" in {
        forAll(list) {
          case (id, json) =>
            eventually {
              cl.get[Json](index, id).ioValue.value shouldEqual json
            }
        }
      }

      "return none when fetch documents that do not exist" in {
        cl.get[Json](index, genString()).ioValue shouldEqual None
      }

      "search for some specific keys and values" in {
        val (_, json) = list.head
        val query = jsonContentOf(
          "/commons/es/query.json",
          Map(
            Pattern.quote("{{value1}}") -> getValue("key", json),
            Pattern.quote("{{value2}}") -> getValue("key2", json)
          )
        )
        val qrs = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, json)))
        cl.search[Json](query, Set(indexSanitized))(p).ioValue shouldEqual qrs
      }

      "search for some specific keys and values with wildcard index" in {
        val (_, json) = list.head
        val query = jsonContentOf(
          "/commons/es/query.json",
          Map(
            Pattern.quote("{{value1}}") -> getValue("key", json),
            Pattern.quote("{{value2}}") -> getValue("key2", json)
          )
        )
        val qrs = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, json)))
        cl.search[Json](query, Set(indexSanitized.take(5) + "*"))(p).ioValue shouldEqual qrs
      }

      "search on an index which does not exist" in {
        val qrs = ScoredQueryResults(0L, 0f, List.empty)

        cl.search[Json](matchAll, Set("non_exists"))(p).ioValue shouldEqual qrs
      }

      "search which returns only specified fields" in {
        val (_, json) = list.head
        val query = jsonContentOf(
          "/commons/es/query.json",
          Map(
            Pattern.quote("{{value1}}") -> getValue("key", json),
            Pattern.quote("{{value2}}") -> getValue("key2", json)
          )
        )
        val expectedJson = Json.obj("key" -> Json.fromString(getValue("key", json)))
        val qrs          = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, expectedJson)))
        cl.search[Json](query)(p, fields = Set("key")).ioValue shouldEqual qrs
      }

      "search all elements sorted in order ascending" in {
        val elems = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))
        val token = Json.arr(Json.fromString(getValue("key", elems(2))))

        val qrs =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))
        cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key")))).ioValue shouldEqual qrs
      }
      "search all elements sorted in order descending" in {
        val elems = list.map(_._2).sortWith(getValue("key", _) > getValue("key", _))
        val token = Json.arr(Json.fromString(getValue("key", elems(2))))

        val qrs =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))
        cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("-key")))).ioValue shouldEqual qrs
      }

      "search elements with sort_after" in {
        val elems = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))
        val token = Json.arr(Json.fromString(getValue("key", elems(2))))

        val qrs1 =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))

        val results1: QueryResults[Json] =
          cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key")))).ioValue

        results1 shouldEqual qrs1

        val sort                  = parse(results1.token.value).toOption.value
        val searchAfterPagination = Pagination(sort, 3)
        val token2                = Json.arr(Json.fromString(getValue("key", elems(5))))

        val qrs2 = UnscoredQueryResults(
          elems.length.toLong,
          elems.slice(3, 6).map(UnscoredQueryResult.apply),
          Some(token2.noSpaces)
        )

        val results2 = cl
          .search[Json](matchAll, Set(indexSanitized))(searchAfterPagination, sort = SortList(List(Sort("key"))))
          .ioValue

        results2 shouldEqual qrs2

      }

      "search elements with from" in {
        val elems = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))
        val token = Json.arr(Json.fromString(getValue("key", elems(2))))

        val qrs1 =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))

        val results1 = cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key")))).ioValue

        results1 shouldEqual qrs1

        val searchAfterPagination = Pagination(3, 3)
        val token2                = Json.arr(Json.fromString(getValue("key", elems(5))))

        val qrs2 = UnscoredQueryResults(
          elems.length.toLong,
          elems.slice(3, 6).map(UnscoredQueryResult.apply),
          Some(token2.noSpaces)
        )

        val results2 = cl
          .search[Json](matchAll, Set(indexSanitized))(searchAfterPagination, sort = SortList(List(Sort("key"))))
          .ioValue

        results2 shouldEqual qrs2
      }

      "search which returns 0 values" in {
        val query = jsonContentOf(
          "/commons/es/simple_query.json",
          Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> genString())
        )
        val qrs = UnscoredQueryResults(0L, List.empty)
        cl.search[Json](query, Set(indexSanitized))(p).ioValue shouldEqual qrs

      }

      "search raw all elements sorted in order ascending" in {

        val sortedMatchAll = Json.obj(
          "query" -> Json.obj("match_all" -> Json.obj()),
          "sort" -> Json.arr(
            Json.obj(
              "key" -> Json.obj(
                "order" -> Json.fromString("asc")
              )
            )
          )
        )
        val elems =
          list.sortWith((e1, e2) => getValue("key", e1._2) < getValue("key", e2._2))

        val json = cl.searchRaw(sortedMatchAll, Set(indexSanitized)).ioValue
        val expectedResponse = jsonContentOf("/commons/es/elastic_search_response.json").mapObject { obj =>
          obj
            .add("took", json.asObject.value("took").value)
            .add(
              "hits",
              Json.obj(
                "total"     -> Json.obj("value" -> Json.fromInt(10), "relation" -> Json.fromString("eq")),
                "max_score" -> Json.Null,
                "hits" -> Json.arr(
                  elems.map { e =>
                    Json.obj(
                      "_index"  -> Json.fromString(cl.sanitize(index, allowWildCard = false)),
                      "_type"   -> Json.fromString("_doc"),
                      "_id"     -> Json.fromString(e._1),
                      "_score"  -> Json.Null,
                      "_source" -> e._2,
                      "sort"    -> Json.arr(Json.fromString(getValue("key", e._2)))
                    )
                  }: _*
                )
              )
            )
        }
        json shouldEqual expectedResponse
      }

      "search raw all elements sorted in order descending" in {
        val sortedMatchAll = Json.obj(
          "query" -> Json.obj("match_all" -> Json.obj()),
          "sort" -> Json.arr(
            Json.obj(
              "key" -> Json.obj(
                "order" -> Json.fromString("desc")
              )
            )
          )
        )
        val elems =
          list.sortWith((e1, e2) => getValue("key", e1._2) > getValue("key", e2._2))

        val json = cl.searchRaw(sortedMatchAll, Set(indexSanitized)).ioValue
        val expectedResponse = jsonContentOf("/commons/es/elastic_search_response.json").mapObject { obj =>
          obj
            .add("took", json.asObject.value("took").value)
            .add(
              "hits",
              Json.obj(
                "total"     -> Json.obj("value" -> Json.fromInt(10), "relation" -> Json.fromString("eq")),
                "max_score" -> Json.Null,
                "hits" -> Json.arr(
                  elems.map { e =>
                    Json.obj(
                      "_index"  -> Json.fromString(cl.sanitize(index, allowWildCard = false)),
                      "_type"   -> Json.fromString("_doc"),
                      "_id"     -> Json.fromString(e._1),
                      "_score"  -> Json.Null,
                      "_source" -> e._2,
                      "sort"    -> Json.arr(Json.fromString(getValue("key", e._2)))
                    )
                  }: _*
                )
              )
            )
        }
        json shouldEqual expectedResponse

      }

      "return ElasticClientError when query is wrong" in {

        val query = Json.obj("query" -> Json.obj("other" -> Json.obj()))
        val result: ElasticSearchClientError =
          cl.searchRaw(query, Set(indexSanitized)).failed[ElasticSearchClientError]
        result.status shouldEqual StatusCodes.BadRequest
        parse(result.body).toOption.value shouldEqual jsonContentOf("/commons/es/elastic_client_error.json")

      }

      val listModified = list.map {
        case (id, json) => id -> (json deepMerge Json.obj("key" -> Json.fromString(genString())))
      }

      "update key field on documents" in {
        forAll(listModified) {
          case (id, json) =>
            val updateScript = jsonContentOf("/commons/es/update.json", Map(Pattern.quote("{{value}}") -> getValue("key", json)))
            cl.update(index, id, updateScript).ioValue shouldEqual ()
            cl.get[Json](index, id).ioValue.value shouldEqual json
            val jsonWithKey = Json.obj("key" -> Json.fromString(getValue("key", json)))
            cl.get[Json](index, id, include = Set("key")).ioValue.value shouldEqual jsonWithKey
            cl.get[Json](index, id, exclude = Set("key2")).ioValue.value shouldEqual jsonWithKey
            cl.get[Json](index, id, include = Set("key"), exclude = Set("key2")).ioValue.value shouldEqual jsonWithKey
        }
      }

      val mapModified = list.map {
        case (id, json) => id -> (json deepMerge Json.obj("key" -> Json.fromString(genString())))
      }.toMap

      "update key field on documents from query" in {
        forAll(listModified) {
          case (id, json) =>
            val query =
              jsonContentOf(
                "/commons/es/simple_query.json",
                Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> getValue("key", json))
              )
            val updateScript =
              jsonContentOf("/commons/es/update.json", Map(Pattern.quote("{{value}}") -> getValue("key", mapModified(id))))

            cl.updateDocuments(Set(indexSanitized), query, updateScript).ioValue shouldEqual ()
            cl.get[Json](index, id).ioValue.value shouldEqual mapModified(id)
        }
      }

      "delete documents" in {
        forAll(mapModified.toList.take(3)) {
          case (id, _) =>
            cl.delete(index, id).ioValue shouldEqual true
            cl.get[Json](index, id).ioValue shouldEqual None
        }
      }

      "delete not existing document" in {
        cl.delete(index, genString()).ioValue shouldEqual false
      }

      "delete documents from query" in {
        forAll(mapModified.toList.drop(3)) {
          case (id, json) =>
            val query =
              jsonContentOf(
                "/commons/es/simple_query.json",
                Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> getValue("key", json))
              )
            cl.deleteDocuments(Set(indexSanitized), query).ioValue shouldEqual ()
            cl.get[Json](index, id).ioValue shouldEqual None
        }
      }

      "add several bulk operations" in {
        val toUpdate   = genString() -> genJson("key", "key1")
        val toOverride = genString() -> genJson("key", "key2")
        val toDelete   = genString() -> genJson("key", "key3")
        val list       = List(toUpdate, toOverride, toDelete)
        val ops        = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
        cl.bulk(ops).ioValue shouldEqual ()

        val updated = Json.obj("key1" -> Json.fromString("updated"))
        cl.bulk(
            List(
              BulkOp.Delete(indexSanitized, toDelete._1),
              BulkOp.Index(indexSanitized, toOverride._1, updated),
              BulkOp.Update(indexSanitized, toUpdate._1, Json.obj("doc" -> updated))
            )
          )
          .ioValue shouldEqual ()
        eventually {
          cl.get[Json](index, toUpdate._1).ioValue.value shouldEqual toUpdate._2.deepMerge(updated)
        }
        eventually {
          cl.get[Json](index, toOverride._1).ioValue.value shouldEqual updated
        }
        eventually {
          cl.get[Json](index, toDelete._1).ioValue shouldEqual None
        }
      }

      "do nothing when BulkOp list is empty" in {
        cl.bulk(List.empty).ioValue shouldEqual ()
      }

      "add and fetch ids with non UTF-8 characters" in {
        val list = List.fill(5)(genIndexString() -> genJson("key", "key1"))
        val ops  = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
        cl.bulk(ops).ioValue shouldEqual ()

        forAll(list) {
          case (id, json) =>
            eventually {
              cl.get[Json](index, id).ioValue.value shouldEqual json
            }
        }

        forAll(list) {
          case (id, _) =>
            cl.update(index, id, Json.obj("doc" -> Json.obj("id" -> Json.fromString(id)))).ioValue
        }

        forAll(list) {
          case (id, json) =>
            eventually {
              cl.get[Json](index, id).ioValue.value shouldEqual json.deepMerge(
                Json.obj("id" -> Json.fromString(id))
              )
            }
        }

        forAll(list) {
          case (id, _) => cl.delete(index, id).ioValue
        }
      }

      "fail on bulk operation" in {
        val toUpdate = genString() -> genJson("key", "key1")
        val toDelete = genString() -> genJson("key", "key3")
        val list     = List(toUpdate, toDelete)
        val ops      = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
        cl.bulk(ops).ioValue shouldEqual ()

        val updated = Json.obj("key1" -> Json.fromString("updated"))
        cl.bulk(
            List(
              BulkOp.Delete(indexSanitized, toDelete._1),
              BulkOp.Update(genString(), toUpdate._1, Json.obj("doc" -> updated))
            )
          )
          .failed[ElasticSearchClientError]
      }
    }
  }
}
