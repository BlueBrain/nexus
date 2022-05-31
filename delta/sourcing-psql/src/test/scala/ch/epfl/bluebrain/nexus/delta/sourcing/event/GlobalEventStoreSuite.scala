package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{Arithmetic, DoobieAssertions, DoobieFixture, MonixBioSuite}
import doobie.implicits._

import java.time.Instant
import scala.concurrent.duration._

class GlobalEventStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = GlobalEventStore[String, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    QueryConfig(10, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val event1 = Plus("id", 1, 12, Instant.EPOCH, Anonymous)
  private val event2 = Minus("id", 2, 3, Instant.EPOCH, alice)
  private val event3 = Plus("id", 3, 4, Instant.EPOCH, alice)
  private val event4 = Minus("id2", 1, 4, Instant.EPOCH, Anonymous)

  private def assertCount = sql"select count(*) from global_events".query[Int].unique.transact(xas.read).assert(4)

  test("Save events successfully") {
    for {
      _ <- List(event1, event2, event3, event4).traverse(store.save).transact(xas.write)
      _ <- assertCount
    } yield ()
  }

  test("Fail when the PK already exists") {
    for {
      _ <- store.save(Plus("id", 2, 5, Instant.EPOCH, Anonymous)).transact(xas.write).expectUniqueViolation
      _ <- assertCount
    } yield ()
  }

  test("Fetch all events for a given id") {
    store.history("id").assert(event1, event2, event3)
  }

  test("Fetch all events for a given id up to revision 2") {
    store.history("id", 2).assert(event1, event2)
  }

  test("Get an empty stream for a unknown id") {
    store.history("xxx", 2).assertEmpty
  }

}
