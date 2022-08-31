package ch.epfl.bluebrain.nexus.delta.sourcing.store

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.Total
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import munit.AnyFixture

import scala.concurrent.duration._
import java.time.Instant

class GlobalStateStoreSuite extends BioSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = GlobalStateStore[String, Total](
    Arithmetic.entityType,
    Total.serializer,
    QueryConfig(1, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val state1        = Total("id1", 1, 5, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = Total("id2", 1, 12, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val updatedState1 = Total("id1", 2, 42, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val envelope1 = Envelope(Arithmetic.entityType, "id1", 1, state1, Instant.EPOCH, Offset.at(1L))
  private val envelope2 = Envelope(Arithmetic.entityType, "id2", 1, state2, Instant.EPOCH, Offset.at(2L))
  private val envelope3 = Envelope(Arithmetic.entityType, "id1", 2, updatedState1, Instant.EPOCH, Offset.at(3L))

  private def assertCount(expected: Int) =
    sql"select count(*) from global_states".query[Int].unique.transact(xas.read).assert(expected)

  test("Save state 1 and state 2 successfully") {
    for {
      _ <- List(state1, state2).traverse(store.save).transact(xas.write)
      _ <- assertCount(2)
    } yield ()
  }

  test("List ids") {
    GlobalStateStore.listIds[String](Arithmetic.entityType, xas.read).assert("id1", "id2")
  }

  test("get state 1") {
    store.get("id1").assertSome(state1)
  }

  test("Fetch all current states from the beginning") {
    store.currentStates(Offset.Start).assert(envelope1, envelope2)
  }

  test("Fetch all current states from offset 2") {
    store.currentStates(Offset.at(1L)).assert(envelope2)
  }

  test("Fetch all states from the beginning") {
    store.states(Offset.Start).take(2).assert(envelope1, envelope2)
  }

  test("Fetch all states from offset 2") {
    store.states(Offset.at(1L)).take(1).assert(envelope2)
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.save(updatedState1).transact(xas.write)
      _ <- assertCount(2)
      _ <- store.get("id1").assertSome(updatedState1)
    } yield ()
  }

  test("Fetch all current states from the beginning after updating state 1") {
    store.currentStates(Offset.Start).assert(envelope2, envelope3)
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete("id2").transact(xas.write)
      _ <- assertCount(1)
      _ <- store.get("id2").assertNone
    } yield ()
  }

  test("Fetch all current states from the beginning after deleting state 2") {
    store.currentStates(Offset.Start).assert(envelope3)
  }

}
