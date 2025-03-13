package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ElemQueryConfig.{DelayConfig, PassivationConfig, StopConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectActivitySignals
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import fs2.concurrent.SignallingRef

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RefreshOrStopSuite extends NexusSuite {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private val org               = Label.unsafe("org")
  private val project           = ProjectRef.unsafe("org", "proj")
  private val stopConfig        = StopConfig(20)
  private val delayConfig       = DelayConfig(20, 50.millis)
  private val passivationConfig = PassivationConfig(20, 50.millis)

  private def activitySignals(signal: SignallingRef[IO, Boolean]) =
    new ProjectActivitySignals {
      override def apply(project: ProjectRef): IO[Option[SignallingRef[IO, Boolean]]] = IO.some(signal)
      override def activityMap: IO[Map[ProjectRef, Boolean]]                          = IO.pure(Map.empty)
    }

  test("A stop config returns a stop outcome for the different scopes") {
    val expected = RefreshOrStop.Outcome.Stopped
    for {
      _ <- RefreshOrStop(Scope.root, stopConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
      _ <- RefreshOrStop(Scope.Org(org), stopConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
      _ <- RefreshOrStop(Scope.Project(project), stopConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
    } yield ()
  }

  test("A delay config returns a delayed outcome for the different scopes") {
    val expected = RefreshOrStop.Outcome.Delayed
    for {
      _ <- RefreshOrStop(Scope.root, delayConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
      _ <- RefreshOrStop(Scope.Org(org), delayConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
      _ <- RefreshOrStop(Scope.Project(project), delayConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
    } yield ()
  }

  test("A passivation config fails for root and org scopes") {
    for {
      _ <-
        RefreshOrStop(Scope.root, passivationConfig, ProjectActivitySignals.noop).run.intercept[IllegalStateException]
      _ <- RefreshOrStop(Scope.Org(org), passivationConfig, ProjectActivitySignals.noop).run
             .intercept[IllegalStateException]
    } yield ()
  }

  test("A passivation config returns a no signal outcome when no signal is available") {
    val expected = RefreshOrStop.Outcome.NoSignal
    RefreshOrStop(Scope.Project(project), passivationConfig, ProjectActivitySignals.noop).run.assertEquals(expected)
  }

  test("A passivation config returns a delayed passivation outcome when no signal is available") {
    val expected = RefreshOrStop.Outcome.DelayedPassivation
    for {
      signal               <- SignallingRef.of[IO, Boolean](true)
      projectActivitySignal = activitySignals(signal)
      _                    <- RefreshOrStop(Scope.Project(project), passivationConfig, projectActivitySignal).run.assertEquals(expected)
    } yield ()
  }

  test("A passivation config returns eventually a passivated outcome when a signal gets activated") {
    val expected = RefreshOrStop.Outcome.Passivated
    for {
      signal               <- SignallingRef.of[IO, Boolean](false)
      obtained             <- Ref.of[IO, Option[RefreshOrStop.Outcome]](None)
      projectActivitySignal = activitySignals(signal)
      _                    <- RefreshOrStop(Scope.Project(project), passivationConfig, projectActivitySignal).run.flatTap { outcome =>
                                obtained.set(Some(outcome))
                              }.start
      _                    <- signal.set(true)
      _                    <- obtained.get.assertEquals(Some(expected)).eventually
    } yield ()
  }

  test("Safely add two durations and return the result") {
    val d1       = FiniteDuration(42L, TimeUnit.NANOSECONDS)
    val d2       = FiniteDuration(8L, TimeUnit.NANOSECONDS)
    val expected = FiniteDuration(50L, TimeUnit.NANOSECONDS)
    assertEquals(RefreshOrStop.safeAdd(d1, d2), Some(expected))
  }

  test("Trigger an overflow and handle the error") {
    val d1 = FiniteDuration(Long.MaxValue - 5L, TimeUnit.NANOSECONDS)
    val d2 = FiniteDuration(8L, TimeUnit.NANOSECONDS)
    assertEquals(RefreshOrStop.safeAdd(d1, d2), None)
  }

}
