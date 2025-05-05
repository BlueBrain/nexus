package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, NexusElasticsearchSuite}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexLabel, QueryBuilder, Refresh}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailureReason
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import fs2.Chunk
import io.circe.Json
import munit.AnyFixture
import org.http4s.Query

import java.time.Instant
import scala.concurrent.duration.*

class ElasticSearchSinkSuite extends NexusElasticsearchSuite with ElasticSearchClientSetup.Fixture with CirceLiteral {

  override def munitFixtures: Seq[AnyFixture[?]] = List(esClient)

  private def createSink(index: IndexLabel) =
    ElasticSearchSink.states(client, BatchConfig(2, 50.millis), index, Refresh.True)

  private val membersEntity = EntityType("members")
  private val index         = IndexLabel.unsafe("test_members")

  private lazy val client = esClient()
  private lazy val sink   = createSink(index)

  private val alice = (nxv + "alice", json"""{"name": "Alice", "age": 25 }""")
  private val bob   = (nxv + "bob", json"""{"name": "Bob", "age": 32 }""")
  private val brian = (nxv + "brian", json"""{"name": "Brian", "age": 19 }""")
  private val judy  = (nxv + "judy", json"""{"name": "Judy", "age": 47 }""")

  private val project = ProjectRef.unsafe("bbp", "members")

  private val members = Set(alice, bob, brian, judy)

  val rev = 1

  private def asChunk(values: Iterable[(Iri, Json)]) =
    Chunk.from(values).zipWithIndex.map { case ((id, json), index) =>
      SuccessElem(membersEntity, id, project, Instant.EPOCH, Offset.at(index.toLong + 1), json, rev)
    }

  private def dropped(id: Iri, offset: Offset) =
    DroppedElem(membersEntity, id, project, Instant.EPOCH, offset, rev)

  test("Create the index") {
    client.createIndex(index, None, None).assertEquals(true)
  }

  test("Index a chunk of documents and retrieve them") {
    val chunk = asChunk(members)

    for {
      _ <- sink.apply(chunk).assertEquals(chunk.map(_.void))
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.empty)
             .map(_.sources.toSet)
             .assertEquals(members.flatMap(_._2.asObject))
    } yield ()
  }

  test("Delete dropped items from the index") {
    val chunk = Chunk(brian, alice).map { case (id, _) => dropped(id, Offset.at(members.size.toLong + 1)) }

    for {
      _ <- sink.apply(chunk).assertEquals(chunk.map(_.void))
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.empty)
             .map(_.sources.toSet)
             .assertEquals(Set(bob, judy).flatMap(_._2.asObject))
    } yield ()
  }

  test("Report errors when a invalid json is submitted") {
    val failed         = FailedElem(
      membersEntity,
      nxv + "fail",
      project,
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
          SuccessElem(membersEntity, id, project, Instant.EPOCH, Offset.at(members.size.toLong + 1), json, rev)
        }
      )
    )

    for {
      result  <- sink.apply(chunk).map(_.toList)
      _        =
        assertEquals(result.size, 3, "3 elements were submitted to the sink, we expect 3 elements in the result chunk.")
      // The failed elem should be return intact
      _        = assertEquals(Some(failed), result.headOption)
      // The invalid one should hold the Elasticsearch error
      _        = result.lift(1) match {
                   case Some(f: FailedElem) =>
                     f.throwable match {
                       case reason: FailureReason =>
                         assertEquals(reason.`type`, "IndexingFailure")
                         val detailKeys = reason.value.asObject.map(_.keys.toSet)
                         assertEquals(detailKeys, Some(Set("type", "reason", "caused_by")))
                       case t                     => fail(s"An indexing failure was expected, got '$t'", t)
                     }
                   case other               => fail(s"A failed elem was expected, got '$other'")
                 }
      // The valid one should remain a success and hold a Unit value
      _        = assert(result.lift(2).flatMap(_.toOption).contains(()))
      expected = Set(bob, judy, alice).flatMap(_._2.asObject)
      _       <- client
                   .search(QueryBuilder.empty, Set(index.value), Query.empty)
                   .map(_.sources.toSet)
                   .assertEquals(expected)
    } yield ()
  }

  test("When the same resource appears twice in a chunk, only the last update prevails") {
    val index     = IndexLabel.unsafe("test_last_update")
    val charlie   = (nxv + "charlie", json"""{"name": "Charlie", "age": 34 }""")
    val rose      = (nxv + "rose", json"""{"name": "Rose", "age": 66 }""")
    val charlie_2 = (nxv + "charlie", json"""{"name": "Charlie M.", "age": 35 }""")

    val chunk = asChunk(List(charlie, rose, charlie_2))
    val sink  = ElasticSearchSink.states(client, BatchConfig(2, 50.millis), index, Refresh.True)

    for {
      _ <- client.createIndex(index, None, None).assertEquals(true)
      _ <- sink.apply(chunk).assertEquals(chunk.map(_.void))
      _ <- client.getSource[Json](index, charlie_2._1.toString).assertEquals(Some(charlie_2._2))
    } yield ()
  }

  test("When the same resource appears twice in a chunk, only the last deletion prevails") {
    val index   = IndexLabel.unsafe("test_last_delete")
    val charlie = (nxv + "charlie", json"""{"name": "Charlie", "age": 34 }""")
    val rose    = (nxv + "rose", json"""{"name": "Rose", "age": 66 }""")

    val indexingChunk = asChunk(List(charlie, rose))
    val deleteChunk   = Chunk.singleton(dropped(charlie._1, Offset.at(indexingChunk.size.toLong + 1)))

    val chunk = Chunk.concat(List(indexingChunk, deleteChunk))

    val sink = ElasticSearchSink.states(client, BatchConfig(2, 50.millis), index, Refresh.True)

    for {
      _ <- client.createIndex(index, None, None).assertEquals(true)
      _ <- sink.apply(chunk).assertEquals(chunk.map(_.void))
      _ <- client.getSource[Json](index, charlie._1.toString).assertEquals(None)
    } yield ()
  }

}
