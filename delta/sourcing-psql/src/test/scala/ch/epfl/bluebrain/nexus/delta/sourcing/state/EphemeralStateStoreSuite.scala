package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, Message}
import ch.epfl.bluebrain.nexus.delta.sourcing.Message.MessageState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class EphemeralStateStoreSuite extends BioSuite with Doobie.Fixture with Doobie.Assertions {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = EphemeralStateStore[String, MessageState](
    Message.entityType,
    MessageState.serializer,
    5.seconds,
    xas
  )

  private val project1 = ProjectRef.unsafe("org", "proj1")

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val message1 = MessageState("m1", project1, "Hello, world !", alice, Instant.EPOCH, Anonymous)

  private val message2 = MessageState("m2", project1, "Bye !", alice, Instant.EPOCH.plusSeconds(60L), Anonymous)

  private lazy val deleteExpired = new DeleteExpired(xas)(IOFixedClock.ioClock(Instant.EPOCH.plusSeconds(6L)))

  test("save the states") {
    for {
      _ <- store.save(message1).transact(xas.write).assert(())
      _ <- store.save(message2).transact(xas.write).assert(())
    } yield ()
  }

  test("get the states") {
    for {
      _ <- store.get(project1, "m1").assertSome(message1)
      _ <- store.get(project1, "m2").assertSome(message2)
      _ <- store.get(project1, "mx").assertNone
    } yield ()
  }

  test("delete expired state m1") {
    for {
      _ <- deleteExpired()
      _ <- store.get(project1, "m1").assertNone
      _ <- store.get(project1, "m2").assertSome(message2)
    } yield ()
  }
}
