package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.Total
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class GlobalStateStoreSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = GlobalStateStore[Iri, Total](
    Arithmetic.entityType,
    Total.serializer,
    QueryConfig(1, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val id1 = nxv + "id1"
  private val id2 = nxv + "id2"

  private val state1        = Total(id1, 1, 5, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = Total(id2, 1, 12, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val updatedState1 = Total(id1, 2, 42, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private def assertCount(expected: Int) =
    sql"select count(*) from global_states".query[Int].unique.transact(xas.read).assertEquals(expected)

  test("Save state 1 and state 2 successfully") {
    for {
      _ <- List(state1, state2).traverse(store.save).transact(xas.write)
      _ <- assertCount(2)
    } yield ()
  }

  test("List ids") {
    GlobalStateStore.listIds(Arithmetic.entityType, xas.read).assert(List(id1, id2))
  }

  test("get state 1") {
    store.get(id1).assertEquals(Some(state1))
  }

  test("Fetch all current states from the beginning") {
    store.currentStates(Offset.Start).assert(state1, state2)
  }

  test("Fetch all current states from offset 2") {
    store.currentStates(Offset.at(1L)).assert(state2)
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.save(updatedState1).transact(xas.write)
      _ <- assertCount(2)
      _ <- store.get(id1).assertEquals(Some(updatedState1))
    } yield ()
  }

  test("Fetch all current states from the beginning after updating state 1") {
    store.currentStates(Offset.Start).assert(state2, updatedState1)
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete(id2).transact(xas.write)
      _ <- assertCount(1)
      _ <- store.get(id2).assertEquals(None)
    } yield ()
  }

  test("Fetch all current states from the beginning after deleting state 2") {
    store.currentStates(Offset.Start).assert(updatedState1)
  }

}
