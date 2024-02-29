package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticCommand.{Add, Boom, Never, Subtract}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticRejection.{AlreadyExists, NegativeTotal, NotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.{ArithmeticCommand, ArithmeticEvent, ArithmeticRejection, Total}
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.EvaluationTimeout
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.GlobalEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import scala.concurrent.duration._

class GlobalEventLogSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Delay(500.millis))

  private lazy val eventStore = GlobalEventStore[Iri, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    queryConfig,
    xas
  )

  private lazy val stateStore = GlobalStateStore[Iri, Total](
    Arithmetic.entityType,
    Total.serializer,
    queryConfig,
    xas
  )

  private val maxDuration = 100.millis

  private lazy val eventLog: GlobalEventLog[Iri, Total, ArithmeticCommand, ArithmeticEvent, ArithmeticRejection] =
    GlobalEventLog(
      eventStore,
      stateStore,
      Arithmetic.stateMachine,
      AlreadyExists,
      maxDuration,
      xas
    )

  private val plus2  = Plus(1, 2)
  private val plus3  = Plus(2, 3)
  private val minus4 = Minus(3, 4)

  private val total1 = Total(1, 2)
  private val total2 = Total(2, 5)
  private val total3 = Total(3, 1)

  private val id = nxv + "id"

  test("Raise an error with a non-existent " + id) {
    eventLog.stateOr(nxv + "xxx", NotFound).interceptEquals(NotFound)
  }

  test("Evaluate successfully a command and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate(id, Add(2)).assertEquals((plus2, total1))
      _ <- eventStore.history(id).assert(plus2)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total1)
    } yield ()
  }

  test("Evaluate successfully another and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate(id, Add(3)).assertEquals((plus3, total2))
      _ <- eventStore.history(id).assert(plus2, plus3)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total2)
    } yield ()
  }

  test("Reject a command and persist nothing") {
    for {
      _ <- eventLog.evaluate(id, Subtract(8)).interceptEquals(NegativeTotal(-3))
      _ <- eventStore.history(id).assert(plus2, plus3)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total2)
    } yield ()
  }

  test("Raise an error and persist nothing") {
    val boom = Boom("fail")
    for {
      _ <- eventLog.evaluate(id, boom).intercept[RuntimeException]
      _ <- eventStore.history(id).assert(plus2, plus3)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total2)
    } yield ()
  }

  test("Get a timeout and persist nothing") {
    for {
      _ <- eventLog.evaluate(id, Never).interceptEquals(EvaluationTimeout(Never, maxDuration))
      _ <- eventStore.history(id).assert(plus2, plus3)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total2)
    } yield ()
  }

  test("Dry run successfully a command without persisting anything") {
    for {
      _ <- eventLog.dryRun(id, Subtract(4)).assertEquals((minus4, total3))
      _ <- eventStore.history(id).assert(plus2, plus3)
      _ <- eventLog.stateOr(id, NotFound).assertEquals(total2)
    } yield ()
  }

  test("Get state at the specified revision") {
    eventLog.stateOr(id, 1, NotFound, RevisionNotFound).assertEquals(total1)
  }

  test("Raise an error with a non-existent " + id) {
    eventLog.stateOr(nxv + "xxx", 1, NotFound, RevisionNotFound).interceptEquals(NotFound)
  }

  test(s"Raise an error when providing a nonexistent revision") {
    eventLog.stateOr(id, 10, NotFound, RevisionNotFound).interceptEquals(RevisionNotFound(10, 2))
  }

}
