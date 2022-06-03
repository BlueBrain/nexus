package ch.epfl.bluebrain.nexus.delta.sourcing.store

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.Total
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.{Arithmetic, DoobieAssertions, DoobieFixture, MonixBioSuite}
import doobie.implicits._

import scala.concurrent.duration._

import java.time.Instant

class GlobalStateStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = GlobalStateStore[String, Total](
    Arithmetic.entityType,
    Total.serializer,
    QueryConfig(10, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val state1        = Total("id1", 1, 5, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = Total("id2", 1, 12, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val updatedState1 = Total("id1", 2, 42, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private def assertCount(expected: Int) =
    sql"select count(*) from global_states".query[Int].unique.transact(xas.read).assert(expected)

  test("Save state 1 and state 2 successfully") {
    for {
      _ <- List(state1, state2).traverse(store.save).transact(xas.write)
      _ <- assertCount(2)
    } yield ()
  }

  test("get state 1") {
    store.get("id1").assertSome(state1)
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.save(updatedState1).transact(xas.write)
      _ <- assertCount(2)
      _ <- store.get("id1").assertSome(updatedState1)
    } yield ()
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete("id2").transact(xas.write)
      _ <- assertCount(1)
      _ <- store.get("id2").assertNone
    } yield ()
  }

}
