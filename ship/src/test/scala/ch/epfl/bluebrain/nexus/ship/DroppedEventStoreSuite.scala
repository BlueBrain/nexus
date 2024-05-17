package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import io.circe.syntax.KeyOps
import munit.AnyFixture

import java.time.Instant

class DroppedEventStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas   = doobie()
  private lazy val store = new DroppedEventStore(xas)

  test("Insert an event and truncate it") {
    val rowEvent = RowEvent(
      Offset.At(5L),
      EntityType("entity"),
      Label.unsafe("org"),
      Label.unsafe("project"),
      nxv + "id",
      3,
      Json.obj("field" := "value"),
      Instant.EPOCH
    )
    for {
      _ <- store.save(rowEvent)
      _ <- store.count.assertEquals(1L)
      _ <- store.truncate
      _ <- store.count.assertEquals(0L)
    } yield ()
  }

}
