package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticCommand.{Add, Subtract}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticRejection.{AlreadyExists, NegativeTotal}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.{ArithmeticEvent, Total}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.{EvaluationConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.GlobalEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore

import scala.concurrent.duration._

class GlobalEventLogSuite extends MonixBioSuite with DoobieFixture {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val eventStore = GlobalEventStore[String, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    QueryConfig(10, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private lazy val stateStore = GlobalStateStore[String, Total](
    Arithmetic.entityType,
    Total.serializer,
    xas
  )

  private val config = EvaluationConfig(100.millis)

  private lazy val eventLog = GlobalEventLog(
    eventStore,
    stateStore,
    Arithmetic.stateMachine,
    AlreadyExists,
    config,
    xas
  )

  private val plus2  = Plus(1, 2)
  private val plus3  = Plus(2, 3)
  private val minus4 = Minus(3, 4)

  private val total1 = Total(1, 2)
  private val total2 = Total(2, 5)
  private val total3 = Total(3, 1)

  test("Evaluate successfully a command and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate("id", Add(2)).assert((plus2, total1))
      _ <- eventStore.history("id").assert(plus2)
      _ <- eventLog.state("id").assertSome(total1)
    } yield ()
  }

  test("Evaluate successfully another and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate("id", Add(3)).assert((plus3, total2))
      _ <- eventStore.history("id").assert(plus2, plus3)
      _ <- eventLog.state("id").assertSome(total2)
    } yield ()
  }

  test("Reject a command and persist nothing") {
    for {
      _ <- eventLog.evaluate("id", Subtract(8)).error(NegativeTotal(-3))
      _ <- eventStore.history("id").assert(plus2, plus3)
      _ <- eventLog.state("id").assertSome(total2)
    } yield ()
  }

  test("Dry run successfully a command without persisting anything") {
    for {
      _ <- eventLog.dryRun("id", Subtract(4)).assert((minus4, total3))
      _ <- eventStore.history("id").assert(plus2, plus3)
      _ <- eventLog.state("id").assertSome(total2)
    } yield ()
  }

  test("Get state at the specified revision") {
    eventLog.state("id", 1).assertSome(total1)
  }

}
