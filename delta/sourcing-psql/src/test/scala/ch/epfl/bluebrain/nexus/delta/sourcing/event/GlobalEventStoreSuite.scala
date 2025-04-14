package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.*

class GlobalEventStoreSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = GlobalEventStore[Iri, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    QueryConfig(2, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val id     = nxv + "id"
  private val id2    = nxv + "id2"
  private val event1 = Plus(id, 1, 12, Instant.EPOCH, Anonymous)
  private val event2 = Minus(id, 2, 3, Instant.EPOCH, alice)
  private val event3 = Plus(id, 3, 4, Instant.EPOCH, alice)
  private val event4 = Minus(id2, 1, 4, Instant.EPOCH, Anonymous)

  private def assertCount =
    sql"select count(*) from global_events".query[Int].unique.transact(xas.read).assertEquals(4)

  test("Save events successfully") {
    for {
      _ <- List(event1, event2, event3, event4).traverse(store.save).transact(xas.write)
      _ <- assertCount
    } yield ()
  }

  test("Fail when the PK already exists") {
    for {
      _ <-
        store.save(Plus(id, 2, 5, Instant.EPOCH, Anonymous)).transact(xas.write).expectUniqueViolation
      _ <- assertCount
    } yield ()
  }

  test("Fetch all events for a given " + id) {
    store.history(id).assert(event1, event2, event3)
  }

  test("Fetch all events for a given " + id + " up to revision 2") {
    store.history(id, 2).assert(event1, event2)
  }

  test("Get an empty stream for a unknown " + id) {
    store.history(nxv + "xxx", 2).assertEmpty
  }

  test(s"Delete events for $id") {
    for {
      _ <- store.delete(id).transact(xas.write)
      _ <- store.history(id).assertSize(0)
    } yield ()
  }

}
