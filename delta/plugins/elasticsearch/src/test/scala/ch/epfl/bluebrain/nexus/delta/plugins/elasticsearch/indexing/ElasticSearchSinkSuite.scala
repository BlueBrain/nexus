package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexLabel, QueryBuilder}
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
  private lazy val sink   = new ElasticSearchSink(client, 2, 50.millis, index)

  private val membersEntity = EntityType("members")
  private val index         = IndexLabel.unsafe("test_members")

  private val alice = ("alice", json"""{"name": "Alice", "age": 25 }""")
  private val bob   = ("bob", json"""{"name": "Bob", "age": 32 }""")
  private val brian = ("brian", json"""{"name": "Brian", "age": 19 }""")
  private val judy  = ("judy", json"""{"name": "Judy", "age": 47 }""")

  val members = Set(alice, bob, brian, judy)

  test("Create the index") {
    client.createIndex(index, None, None).assert(true)
  }

  test("Index a chunk of documents and retrieve them") {
    val chunk = Chunk.iterable(members).zipWithIndex.map { case ((id, json), index) =>
      SuccessElem(membersEntity, id, Instant.EPOCH, Offset.at(index.toLong + 1), json)
    }

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client.refresh(index)
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.Empty)
             .map(_.sources.toSet)
             .assert(members.flatMap(_._2.asObject))
    } yield ()
  }

  test("Delete dropped items from the index") {
    val chunk = Chunk(brian, alice).map { case (id, _) =>
      DroppedElem(membersEntity, id, Instant.EPOCH, Offset.at(members.size.toLong + 1))
    }

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client.refresh(index)
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.Empty)
             .map(_.sources.toSet)
             .assert(Set(bob, judy).flatMap(_._2.asObject))
    } yield ()
  }

  test("Report errors when a invalid json is submitted") {
    val chunk = Chunk(("xxx", json"""{"name": 112, "age": "xxx"}"""), alice).map { case (id, json) =>
      SuccessElem(membersEntity, id, Instant.EPOCH, Offset.at(members.size.toLong + 1), json)
    }

    for {
      _ <- sink
             .apply(chunk)
             .map {
               _.foldLeft(0) {
                 case (acc, _: SuccessElem[Unit]) => acc
                 case (acc, _: DroppedElem)       => acc
                 case (acc, _: FailedElem)        => acc + 1
               }
             }
             .assert(2)
      _ <- client.refresh(index)
      _ <- client
             .search(QueryBuilder.empty, Set(index.value), Query.Empty)
             .map(_.sources.toSet)
             .assert(Set(bob, judy, alice).flatMap(_._2.asObject))
    } yield ()
  }

}
