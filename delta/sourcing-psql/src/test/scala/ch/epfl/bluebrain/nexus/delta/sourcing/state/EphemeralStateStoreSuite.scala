package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Message.MessageState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, Message}
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class EphemeralStateStoreSuite extends CatsEffectSuite with Doobie.Fixture with Doobie.Assertions {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = EphemeralStateStore[Iri, MessageState](
    Message.entityType,
    MessageState.serializer,
    5.seconds,
    xas
  )

  private val project1 = ProjectRef.unsafe("org", "proj1")

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val m1       = nxv + "m1"
  private val message1 = MessageState(m1, project1, "Hello, world !", alice, Instant.EPOCH, Anonymous)

  private val m2       = nxv + "m2"
  private val message2 = MessageState(m2, project1, "Bye !", alice, Instant.EPOCH.plusSeconds(60L), Anonymous)

  private lazy val deleteExpired = new DeleteExpired(xas, FixedClock.atInstant(Instant.EPOCH.plusSeconds(6L)))

  test("save the states") {
    for {
      _ <- store.save(message1).transact(xas.write).assertUnit
      _ <- store.save(message2).transact(xas.write).assertUnit
    } yield ()
  }

  test("get the states") {
    for {
      _ <- store.get(project1, m1).assertSome(message1)
      _ <- store.get(project1, m2).assertSome(message2)
      _ <- store.get(project1, nxv + "mx").assertNone
    } yield ()
  }

  test("delete expired state " + m1) {
    for {
      _ <- deleteExpired()
      _ <- store.get(project1, m1).assertNone
      _ <- store.get(project1, m2).assertSome(message2)
    } yield ()
  }
}
