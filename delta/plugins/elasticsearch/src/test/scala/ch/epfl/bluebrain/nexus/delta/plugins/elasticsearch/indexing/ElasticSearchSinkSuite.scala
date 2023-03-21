package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink.BulkUpdateException
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import fs2.Chunk
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ElasticSearchSinkSuite extends BioSuite with ElasticSearchClientSetup.Fixture with CirceLiteral {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()
  private lazy val sink   = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.True)

  private val membersEntity = EntityType("members")
  private val index         = IndexLabel.unsafe("test_members")

  private val alice = (nxv + "alice", json"""{"name": "Alice", "age": 25 }""")
  private val bob   = (nxv + "bob", json"""{"name": "Bob", "age": 32 }""")
  private val brian = (nxv + "brian", json"""{"name": "Brian", "age": 19 }""")
  private val judy  = (nxv + "judy", json"""{"name": "Judy", "age": 47 }""")

  private val members = Set(alice, bob, brian, judy)

  val rev = 1

  test("Create the index") {
    client.createIndex(index, None, None).assert(true)
  }

  test("Index a chunk of documents and retrieve them") {
    val chunk = Chunk.iterable(members).zipWithIndex.map { case ((id, json), index) =>
      SuccessElem(membersEntity, id, None, Instant.EPOCH, Offset.at(index.toLong + 1), json, rev)
    }

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.Empty)
             .map(_.sources.toSet)
             .assert(members.flatMap(_._2.asObject))
    } yield ()
  }

  test("Delete dropped items from the index") {
    val chunk = Chunk(brian, alice).map { case (id, _) =>
      DroppedElem(membersEntity, id, None, Instant.EPOCH, Offset.at(members.size.toLong + 1), rev)
    }

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.Empty)
             .map(_.sources.toSet)
             .assert(Set(bob, judy).flatMap(_._2.asObject))
    } yield ()
  }

  test("Report errors when a invalid json is submitted") {
    val failed         = FailedElem(
      membersEntity,
      nxv + "fail",
      None,
      Instant.EPOCH,
      Offset.at(1L),
      new IllegalArgumentException("Boom"),
      1
    )
    val invalidElement = (nxv + "xxx", json"""{"name": 112, "age": "xxx"}""")
    val chunk          = Chunk.concat(
      Seq(
        Chunk.singleton(failed),
        Chunk(invalidElement, alice).map { case (id, json) =>
          SuccessElem(membersEntity, id, None, Instant.EPOCH, Offset.at(members.size.toLong + 1), json, rev)
        }
      )
    )

    for {
      result <- sink.apply(chunk).map(_.toList)
      _       =
        assertEquals(result.size, 3, "3 elements were submitted to the sink, we expect 3 elements in the result chunk.")
      // The failed elem should be return intact
      _       = assertEquals(Some(failed), result.headOption)
      // The invalid one should hold the Elasticsearch error
      _       = assert(
                  result.lift(1).flatMap(_.toThrowable).exists(_.isInstanceOf[BulkUpdateException]),
                  "We expect a 'BulkUpdateException' as an error here"
                )
      // The valid one should remain a success and hold a Unit value
      _       = assert(result.lift(2).flatMap(_.toOption).contains(()))
      _      <- client
                  .search(QueryBuilder.empty, Set(index.value), Query.Empty)
                  .map(_.sources.toSet)
                  .assert(Set(bob, judy, alice).flatMap(_._2.asObject))
    } yield ()
  }

}
