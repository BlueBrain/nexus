package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Message.MessageRejection.{AlreadyExists, MessageTooLong, NotFound}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Message.{CreateMessage, MessageRejection, MessageState}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.execution.EvaluationExecution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class EphemeralLogSuite extends CatsEffectSuite with Doobie.Fixture with Doobie.Assertions {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val definition: EphemeralDefinition[Iri, MessageState, CreateMessage, MessageRejection] =
    EphemeralDefinition(
      Message.entityType,
      Message.evaluate,
      MessageState.serializer,
      (_, command) => AlreadyExists(command.id, command.project)
    )

  implicit val ee: EvaluationExecution = EvaluationExecution(timer, contextShift)

  private lazy val log = EphemeralLog(
    definition,
    EphemeralLogConfig(100.millis, 5.hours),
    xas
  )

  private val id      = nxv + "m1"
  private val proj    = ProjectRef.unsafe("org", "proj")
  private val text    = "Hello !"
  private val alice   = User("Alice", Label.unsafe("Wonderland"))
  private val message = MessageState(id, proj, text, alice, Instant.EPOCH, Anonymous)

  private def createMessage(text: String) =
    log.evaluate(proj, id, CreateMessage(id, proj, text, alice))

  test("Raise an error with a non-existent project") {
    log.stateOr(ProjectRef.unsafe("xxx", "xxx"), id, NotFound).intercept(NotFound)
  }

  test("Raise an error with a non-existent id") {
    log.stateOr(proj, nxv + "xxx", NotFound).intercept(NotFound)
  }

  test("Raise an error if the text message is too long and save nothing") {
    for {
      _ <- createMessage("Hello, World !").intercept(MessageTooLong(id, proj))
      _ <- log.stateOr(proj, id, NotFound).intercept(NotFound)
    } yield ()
  }

  test("Evaluate successfully the command and save the message") {
    for {
      _ <- createMessage(text).assertEquals(message)
      _ <- log.stateOr(proj, id, NotFound).assertEquals(message)
    } yield ()
  }

  test("Raise an error if id already exists and save nothing") {
    for {
      _ <- createMessage("Bye").intercept(AlreadyExists(id, proj))
      _ <- log.stateOr(proj, id, NotFound).assertEquals(message)
    } yield ()
  }
}
