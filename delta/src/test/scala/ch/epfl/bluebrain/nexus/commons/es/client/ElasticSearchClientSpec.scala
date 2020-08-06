package ch.epfl.bluebrain.nexus.commons.es.client

import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes, Uri}
import akka.stream.Materializer
import cats.effect.{ContextShift, Effect, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient.BulkOp
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import ch.epfl.bluebrain.nexus.commons.search.{Pagination, QueryResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import distage.DIKey
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import izumi.distage.model.definition.StandardAxis
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ElasticSearchClientSpec
    extends DistageSpecScalatest[IO]
    with ShouldMatchers
    with Randomness
    with Resources
    with OptionValues
    with Inspectors
    with DistageSpecSugar {

  implicit private val cs: ContextShift[IO]                   = IO.contextShift(ExecutionContext.global)
  implicit private val tm: Timer[IO]                          = IO.timer(ExecutionContext.global)
  implicit private val retryConfig: RetryStrategyConfig       =
    RetryStrategyConfig("constant", 100.millis, 100.millis, 10, 100.millis)
  implicit private val qrDecoder: Decoder[QueryResults[Json]] = ElasticSearchDecoder[Json]

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = StandardAxis.testDummyActivation,
      parallelTests = ParallelLevel.Sequential,
      memoizationRoots = Set(DIKey[ElasticSearchClient[IO]]),
      moduleOverrides = new DistageModuleDef("ElasticSearchClientSpec") {
        include(ElasticSearchDockerModule[IO])

        make[HttpClient[IO, HttpResponse]].from { (as: ActorSystem, mt: Materializer) =>
          implicit val a: ActorSystem  = as
          implicit val m: Materializer = mt
          HttpClient.untyped[IO]
        }

        make[HttpClient[IO, Json]].from { (as: ActorSystem, mt: Materializer, uc: UntypedHttpClient[IO]) =>
          implicit val m: Materializer          = mt
          implicit val u: UntypedHttpClient[IO] = uc
          implicit val ec: ExecutionContext     = as.dispatcher
          HttpClient.withUnmarshaller[IO, Json]
        }

        make[HttpClient[IO, QueryResults[Json]]].from {
          (as: ActorSystem, mt: Materializer, uc: UntypedHttpClient[IO]) =>
            implicit val m: Materializer          = mt
            implicit val u: UntypedHttpClient[IO] = uc
            implicit val ec: ExecutionContext     = as.dispatcher
            HttpClient.withUnmarshaller[IO, QueryResults[Json]]
        }

        make[ElasticSearchClient[IO]].from {
          (es: ElasticSearchDocker.Container, as: ActorSystem, mt: Materializer, cl: UntypedHttpClient[IO]) =>
            implicit val ec: ExecutionContext     = as.dispatcher
            implicit val m: Materializer          = mt
            implicit val c: UntypedHttpClient[IO] = cl
            implicit val eff: Effect[IO]          = IO.ioEffect
            val port                              = es.availablePorts.first(ElasticSearchDocker.primaryPort)
            val base: Uri                         = s"http://${port.hostV4}:${port.port}"
            ElasticSearchClient(base)
        }
      },
      configBaseName = "elasticsearch-client-test"
    )

  private def genIndexString(): String =
    genString(length = 20, pool = Vector.range('a', 'f') ++ """ "\<>|,/?""")

  private def genJson(k: String, k2: String): Json      =
    Json.obj(k -> Json.fromString(genString()), k2 -> Json.fromString(genString()))

  private def getValue(key: String, json: Json): String =
    json.hcursor.get[String](key).getOrElse("")

  "An ElasticSearchClient" should {

    val indexPayload   = IO(jsonContentOf("/commons/es/index_payload.json"))
    val mappingPayload = IO(jsonContentOf("/commons/es/mapping_payload.json"))

    "sanitize index names" in { cl: ElasticSearchClient[IO] =>
      forAll(""" "*\<>|,/?""") { ch =>
        cl.sanitize(s"a${ch}a", allowWildCard = false) shouldEqual "a_a"
      }
      cl.sanitize(s"a*a", allowWildCard = true) shouldEqual "a*a"
    }

    "fetch the correct service description" in { cl: ElasticSearchClient[IO] =>
      for {
        sd <- cl.serviceDescription
        _   = sd shouldEqual ServiceDescription("elasticsearch", "7.5.1")
      } yield ()
    }

    "return false when index does not exist" in { cl: ElasticSearchClient[IO] =>
      for {
        exists <- cl.existsIndex(genString())
        _       = exists shouldEqual false
      } yield ()
    }

    "create mappings if index does not exist" in { cl: ElasticSearchClient[IO] =>
      val index = genIndexString()
      for {
        idx     <- indexPayload
        created <- cl.createIndex(index, idx)
        _        = created shouldEqual true
        created <- cl.createIndex(index, idx)
        _        = created shouldEqual false
      } yield ()
    }

    "create mappings and index settings" in { cl: ElasticSearchClient[IO] =>
      val index = genIndexString()
      for {
        idx     <- indexPayload
        created <- cl.createIndex(index, idx)
        _        = created shouldEqual true
        exists  <- cl.existsIndex(index)
        _        = exists shouldEqual true
      } yield ()
    }

    "create mappings" in { cl: ElasticSearchClient[IO] =>
      val index = genIndexString()
      for {
        idx     <- indexPayload
        mapping <- mappingPayload
        created <- cl.createIndex(index)
        _        = created shouldEqual true
        updated <- cl.updateMapping(index, mapping)
        _        = updated shouldEqual true
        updated <- cl.updateMapping(genIndexString(), mapping)
        _        = updated shouldEqual false
        _       <- cl.updateMapping(index, idx).passWhenErrorType[ElasticSearchClientError]

      } yield ()
    }

    "delete index" in { cl: ElasticSearchClient[IO] =>
      val index = genIndexString()
      for {
        idx     <- indexPayload
        created <- cl.createIndex(index, idx)
        _        = created shouldEqual true
        deleted <- cl.deleteIndex(index)
        _        = deleted shouldEqual true
        deleted <- cl.deleteIndex(index)
        _        = deleted shouldEqual false
      } yield ()
    }

    val list          = List.fill(10)(genString() -> genJson("key", "key2"))
    val p: Pagination = Pagination(0, 3)

    "create documents" in { cl: ElasticSearchClient[IO] =>
      val index = genIndexString()
      for {
        idx           <- indexPayload
        indexSanitized = cl.sanitize(index, allowWildCard = false)
        created       <- cl.createIndex(index, idx)
        _              = created shouldEqual true
        ops            = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
        _             <- cl.bulk(ops)
      } yield ()
    }

    "override documents" in { cl: ElasticSearchClient[IO] =>
      val index      = genIndexString()
      val (id, json) = list.head
      for {
        idx           <- indexPayload
        _             <- cl.createIndex(index, idx)
        indexSanitized = cl.sanitize(index, allowWildCard = false)
        _             <- cl.create(indexSanitized, id, json)
        _             <- cl.create(indexSanitized, id, json, overrideIfExists = false).passWhenErrorType[Throwable]
        _             <- cl.create(indexSanitized, id, json)
      } yield ()
    }

    "fetch documents" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
      implicit val jcl: HttpClient[IO, Json] = jsonClient
      val index                              = genIndexString()
      for {
        idx           <- indexPayload
        _             <- cl.createIndex(index, idx)
        indexSanitized = cl.sanitize(index, allowWildCard = false)
        _             <- list.map { case (id, json) => cl.create(indexSanitized, id, json) }.sequence
        _             <- list.map {
                           case (id, json) =>
                             cl.get[Json](indexSanitized, id)
                               .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                               .map(j => j shouldEqual json)
                         }.sequence
      } yield ()
    }

    "return none when fetching documents that do not exist" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
        implicit val jcl: HttpClient[IO, Json] = jsonClient
        val index                              = genIndexString()
        for {
          idx    <- indexPayload
          _      <- cl.createIndex(index, idx)
          result <- cl.get[Json](index, genString())
          _       = result shouldEqual None
        } yield ()
    }

    val index     = genIndexString()
    val (_, json) = list.head
    val query     = jsonContentOf(
      "/commons/es/query.json",
      Map(
        Pattern.quote("{{value1}}") -> getValue("key", json),
        Pattern.quote("{{value2}}") -> getValue("key2", json)
      )
    )
    val matchAll  = Json.obj("query" -> Json.obj("match_all" -> Json.obj()))

    "search for some specific keys and values" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)

        val qrs = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, json)))
        for {
          idx <- indexPayload
          _   <- cl.createIndex(index, idx)
          _   <- list.map { case (id, json) => cl.create(indexSanitized, id, json) }.sequence
          _   <- cl.search[Json](query, Set(indexSanitized))(p).eventually(_ shouldEqual qrs)
        } yield ()
    }

    "search for some specific keys and values with wildcard index" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
        val qrs                                              = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, json)))
        for {
          result <- cl.search[Json](query, Set(indexSanitized.take(5) + "*"))(p)
          _       = result shouldEqual qrs
        } yield ()
    }

    "search on an index that doesn't exist" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val qrs                                              = ScoredQueryResults(0L, 0f, List.empty)
        for {
          result <- cl.search[Json](matchAll, Set("does_not_exist"))(p)
          _       = result shouldEqual qrs
        } yield ()
    }

    "search for some specific keys and values returning specific fields" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
        val expectedJson                                     = Json.obj("key" -> Json.fromString(getValue("key", json)))
        val qrs                                              = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, expectedJson)))
        for {
          result <- cl.search[Json](query, Set(indexSanitized))(p, fields = Set("key"))
          _       = result shouldEqual qrs
        } yield ()
    }

    "search all elements sorted in ascending order" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
        val elems                                            = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))
        val token                                            = Json.arr(Json.fromString(getValue("key", elems(2))))
        val qrs                                              =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))

        for {
          result <- cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key"))))
          _       = result shouldEqual qrs
        } yield ()
    }

    "search all elements sorted in descending order" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
        val elems                                            = list.map(_._2).sortWith(getValue("key", _) > getValue("key", _))
        val token                                            = Json.arr(Json.fromString(getValue("key", elems(2))))
        val qrs                                              =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))

        for {
          result <- cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("-key"))))
          _       = result shouldEqual qrs
        } yield ()
    }

    "search elements with sort_after" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
        val elems                                            = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))
        val token                                            = Json.arr(Json.fromString(getValue("key", elems(2))))

        val qrs1 =
          UnscoredQueryResults(elems.length.toLong, elems.take(3).map(UnscoredQueryResult.apply), Some(token.noSpaces))
        for {
          results1             <- cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key"))))
          _                     = results1 shouldEqual qrs1
          sort                  = parse(results1.token.value).toOption.value
          searchAfterPagination = Pagination(sort, 3)
          token2                = Json.arr(Json.fromString(getValue("key", elems(5))))
          qrs2                  = UnscoredQueryResults(
                                    elems.length.toLong,
                                    elems.slice(3, 6).map(UnscoredQueryResult.apply),
                                    Some(token2.noSpaces)
                                  )
          results2             <-
            cl.search[Json](matchAll, Set(indexSanitized))(searchAfterPagination, sort = SortList(List(Sort("key"))))
          _                     = results2 shouldEqual qrs2
        } yield ()
    }

    "search elements with from" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
      implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
      val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)
      val elems                                            = list.map(_._2).sortWith(getValue("key", _) < getValue("key", _))

      val token                 = Json.arr(Json.fromString(getValue("key", elems(2))))
      val qrs1                  = UnscoredQueryResults(
        elems.length.toLong,
        elems.take(3).map(UnscoredQueryResult.apply),
        Some(token.noSpaces)
      )
      val searchAfterPagination = Pagination(3, 3)
      val token2                = Json.arr(Json.fromString(getValue("key", elems(5))))
      val qrs2                  = UnscoredQueryResults(
        elems.length.toLong,
        elems.slice(3, 6).map(UnscoredQueryResult.apply),
        Some(token2.noSpaces)
      )

      for {
        results1 <- cl.search[Json](matchAll, Set(indexSanitized))(p, sort = SortList(List(Sort("key"))))
        _         = results1 shouldEqual qrs1
        results2 <-
          cl.search[Json](matchAll, Set(indexSanitized))(searchAfterPagination, sort = SortList(List(Sort("key"))))
        _         = results2 shouldEqual qrs2
      } yield ()
    }

    "search expecting 0 results" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, QueryResults[Json]]) =>
      implicit val jcl: HttpClient[IO, QueryResults[Json]] = jsonClient
      val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)

      val query = jsonContentOf(
        "/commons/es/simple_query.json",
        Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> genString())
      )
      val qrs   = UnscoredQueryResults(0L, List.empty)
      for {
        result <- cl.search[Json](query, Set(indexSanitized))(p)
        _       = result shouldEqual qrs
      } yield ()
    }

    "search raw all elements sorted in ascending order" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
        implicit val jcl: HttpClient[IO, Json] = jsonClient
        val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

        val sortedMatchAll = Json.obj(
          "query" -> Json.obj("match_all" -> Json.obj()),
          "sort"  -> Json.arr(
            Json.obj(
              "key" -> Json.obj(
                "order" -> Json.fromString("asc")
              )
            )
          )
        )

        val elems = list.sortWith((e1, e2) => getValue("key", e1._2) < getValue("key", e2._2))

        for {
          result          <- cl.searchRaw(sortedMatchAll, Set(indexSanitized))
          expectedResponse = jsonContentOf("/commons/es/elastic_search_response.json").mapObject { obj =>
                               obj
                                 .add("took", result.asObject.value("took").value)
                                 .add(
                                   "hits",
                                   Json.obj(
                                     "total"     -> Json
                                       .obj("value" -> Json.fromInt(10), "relation" -> Json.fromString("eq")),
                                     "max_score" -> Json.Null,
                                     "hits"      -> Json.arr(
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
          _                = result shouldEqual expectedResponse
        } yield ()
    }

    "search raw all elements sorted in descending order" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
        implicit val jcl: HttpClient[IO, Json] = jsonClient
        val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

        val sortedMatchAll = Json.obj(
          "query" -> Json.obj("match_all" -> Json.obj()),
          "sort"  -> Json.arr(
            Json.obj(
              "key" -> Json.obj(
                "order" -> Json.fromString("desc")
              )
            )
          )
        )

        val elems = list.sortWith((e1, e2) => getValue("key", e1._2) > getValue("key", e2._2))

        for {
          result          <- cl.searchRaw(sortedMatchAll, Set(indexSanitized))
          expectedResponse = jsonContentOf("/commons/es/elastic_search_response.json").mapObject { obj =>
                               obj
                                 .add("took", result.asObject.value("took").value)
                                 .add(
                                   "hits",
                                   Json.obj(
                                     "total"     -> Json
                                       .obj("value" -> Json.fromInt(10), "relation" -> Json.fromString("eq")),
                                     "max_score" -> Json.Null,
                                     "hits"      -> Json.arr(
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
          _                = result shouldEqual expectedResponse
        } yield ()
    }

    "return ElasticClientError when query is wrong" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
        implicit val jcl: HttpClient[IO, Json] = jsonClient
        val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

        val query         = Json.obj("query" -> Json.obj("other" -> Json.obj()))
        val expectedError = jsonContentOf("/commons/es/elastic_client_error.json")
        for {
          result <- cl.searchRaw(query, Set(indexSanitized)).passWhenErrorType[ElasticSearchClientError]
          _       = result.status shouldEqual StatusCodes.BadRequest
          body   <- IO.fromEither(parse(result.body))
          _       = body shouldEqual expectedError
        } yield ()
    }

    val listModified = list.map {
      case (id, json) => id -> (json deepMerge Json.obj("key" -> Json.fromString(genString())))
    }

    "update key field on documents" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
      implicit val jcl: HttpClient[IO, Json] = jsonClient
      val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

      for {
        _ <- listModified.map {
               case (id, json) =>
                 val updateScript = jsonContentOf(
                   "/commons/es/update.json",
                   Map(Pattern.quote("{{value}}") -> getValue("key", json))
                 )
                 cl.update(index, id, updateScript)
             }.sequence
        _ <- listModified.map {
               case (id, json) =>
                 cl.get[Json](indexSanitized, id)
                   .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                   .map(j => j shouldEqual json)
             }.sequence
        _ <- listModified.map {
               case (id, json) =>
                 val jsonWithKey = Json.obj("key" -> Json.fromString(getValue("key", json)))
                 cl.get[Json](indexSanitized, id, include = Set("key"))
                   .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                   .map(j => j shouldEqual jsonWithKey)
             }.sequence
        _ <- listModified.map {
               case (id, json) =>
                 val jsonWithKey = Json.obj("key" -> Json.fromString(getValue("key", json)))
                 cl.get[Json](indexSanitized, id, exclude = Set("key2"))
                   .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                   .map(j => j shouldEqual jsonWithKey)
             }.sequence
        _ <- listModified.map {
               case (id, json) =>
                 val jsonWithKey = Json.obj("key" -> Json.fromString(getValue("key", json)))
                 cl.get[Json](indexSanitized, id, include = Set("key"), exclude = Set("key2"))
                   .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                   .map(j => j shouldEqual jsonWithKey)
             }.sequence
      } yield ()
    }

    val mapModified = list.map {
      case (id, json) => id -> (json deepMerge Json.obj("key" -> Json.fromString(genString())))
    }.toMap

    "update key field on documents from query" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json], jsonQRS: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, Json]               = jsonClient
        implicit val jqr: HttpClient[IO, QueryResults[Json]] = jsonQRS
        val index                                            = genIndexString()
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)

        for {
          idx <- indexPayload
          _   <- cl.createIndex(index, idx)
          _   <- listModified.map { case (id, json) => cl.create(index, id, json) }.sequence
          _   <- listModified.map {
                   case (id, json) =>
                     val query        =
                       jsonContentOf(
                         "/commons/es/simple_query.json",
                         Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> getValue("key", json))
                       )
                     val updateScript =
                       jsonContentOf(
                         "/commons/es/update.json",
                         Map(Pattern.quote("{{value}}") -> getValue("key", mapModified(id)))
                       )
                     cl.search[Json](matchAll, Set(indexSanitized))(Pagination(100))
                       .eventually(qr => qr.total shouldEqual listModified.size) >>
                       cl.updateDocuments(Set(indexSanitized), query, updateScript) >>
                       cl.get[Json](index, id)
                         .flatMap(j => IO.fromOption(j)(new RuntimeException(s"Document for id '$id' not found")))
                         .eventually(j => j shouldEqual mapModified(id))
                 }.sequence
        } yield ()
    }

    "delete documents" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
      implicit val jcl: HttpClient[IO, Json] = jsonClient
      val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

      for {
        _ <- mapModified.toList
               .take(3)
               .map {
                 case (id, _) =>
                   cl.delete(index, id).map(deleted => deleted shouldEqual true)
               }
               .sequence
        _ <- mapModified.toList
               .take(3)
               .map {
                 case (id, _) =>
                   cl.get[Json](indexSanitized, id).map(j => j shouldEqual None)
               }
               .sequence
      } yield ()
    }

    "delete an non existing document" in { cl: ElasticSearchClient[IO] =>
      val indexSanitized = cl.sanitize(index, allowWildCard = false)

      for {
        result <- cl.delete(indexSanitized, genString())
        _       = result shouldEqual false
      } yield ()
    }

    "delete documents from query" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json], jsonQRS: HttpClient[IO, QueryResults[Json]]) =>
        implicit val jcl: HttpClient[IO, Json]               = jsonClient
        implicit val jqr: HttpClient[IO, QueryResults[Json]] = jsonQRS
        val index                                            = genIndexString()
        val indexSanitized                                   = cl.sanitize(index, allowWildCard = false)

        for {
          idx <- indexPayload
          _   <- cl.createIndex(index, idx)
          _   <- mapModified.toList.map { case (id, json) => cl.create(index, id, json) }.sequence
          _   <- cl.search[Json](matchAll, Set(indexSanitized))(Pagination(100))
                   .eventually(qr => qr.total shouldEqual mapModified.size)
          _   <- mapModified.toList
                   .drop(3)
                   .map {
                     case (id, json) =>
                       val query =
                         jsonContentOf(
                           "/commons/es/simple_query.json",
                           Map(Pattern.quote("{{k}}") -> "key", Pattern.quote("{{v}}") -> getValue("key", json))
                         )
                       cl.deleteDocuments(Set(indexSanitized), query) >> cl
                         .get[Json](index, id)
                         .eventually(j => j shouldEqual None)
                   }
                   .sequence
        } yield ()
    }

    "add several bulk operations" in { (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
      implicit val jcl: HttpClient[IO, Json] = jsonClient
      val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

      val toUpdate   = genString() -> genJson("key", "key1")
      val toOverride = genString() -> genJson("key", "key2")
      val toDelete   = genString() -> genJson("key", "key3")
      val list       = List(toUpdate, toOverride, toDelete)
      val ops        = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }

      for {
        _      <- cl.bulk(ops)
        updated = Json.obj("key1" -> Json.fromString("updated"))
        _      <- cl.bulk(
                    List(
                      BulkOp.Delete(indexSanitized, toDelete._1),
                      BulkOp.Index(indexSanitized, toOverride._1, updated),
                      BulkOp.Update(indexSanitized, toUpdate._1, Json.obj("doc" -> updated))
                    )
                  )
        _      <-
          cl.get[Json](indexSanitized, toUpdate._1).eventually(r => r shouldEqual Some(toUpdate._2.deepMerge(updated)))
        _      <- cl.get[Json](indexSanitized, toOverride._1).eventually(r => r shouldEqual Some(updated))
        _      <- cl.get[Json](indexSanitized, toDelete._1).eventually(r => r shouldEqual None)
      } yield ()
    }

    "do nothing when BulkOp list is empty" in { cl: ElasticSearchClient[IO] =>
      for {
        _ <- cl.bulk(List.empty)
      } yield ()
    }

    "add and fetch ids with non UTF-8 characters" in {
      (cl: ElasticSearchClient[IO], jsonClient: HttpClient[IO, Json]) =>
        implicit val jcl: HttpClient[IO, Json] = jsonClient
        val indexSanitized                     = cl.sanitize(index, allowWildCard = false)

        val list = List.fill(5)(genIndexString() -> genJson("key", "key1"))
        val ops  = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }

        for {
          _ <- cl.bulk(ops)
          _ <- list.map {
                 case (id, json) =>
                   cl.get[Json](index, id).eventually(j => j shouldEqual Some(json))
               }.sequence
          _ <- list.map {
                 case (id, _) =>
                   cl.update(index, id, Json.obj("doc" -> Json.obj("id" -> Json.fromString(id))))
               }.sequence
          _ <- list.map {
                 case (id, json) =>
                   cl.get[Json](index, id)
                     .eventually(j => j shouldEqual Some(json.deepMerge(Json.obj("id" -> Json.fromString(id)))))
               }.sequence
          _ <- list.map {
                 case (id, _) =>
                   cl.delete(index, id)
               }.sequence
        } yield ()
    }

    "fail on bulk operation" in { cl: ElasticSearchClient[IO] =>
      val indexSanitized = cl.sanitize(index, allowWildCard = false)

      val toUpdate = genString() -> genJson("key", "key1")
      val toDelete = genString() -> genJson("key", "key3")
      val list     = List(toUpdate, toDelete)
      val ops      = list.map { case (id, json) => BulkOp.Create(indexSanitized, id, json) }
      val updated  = Json.obj("key1" -> Json.fromString("updated"))

      for {
        _ <- cl.bulk(ops)
        _ <- cl.bulk(
                 List(
                   BulkOp.Delete(indexSanitized, toDelete._1),
                   BulkOp.Update(genString(), toUpdate._1, Json.obj("doc" -> updated))
                 )
               )
               .passWhenErrorType[ElasticSearchClientError]
      } yield ()
    }

  }
}
