package ch.epfl.bluebrain.nexus.cli

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import monix.catnap.SchedulerEffect
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CliInitSpec extends AnyWordSpecLike with Matchers {
  implicit private val scheduler: Scheduler       = Scheduler.global
  implicit private val ce: ConcurrentEffect[Task] = Task.catsEffect
  implicit private val cs: ContextShift[Task]     = SchedulerEffect.contextShift[Task](scheduler)
  implicit private val tm: Timer[Task]            = SchedulerEffect.timer[Task](scheduler)
  implicit private val pl: Parallel[Task]         = Task.catsParallel

  "The CLI" should {
    "initialize correctly and display the config" in {
      Cli("config" :: "show" :: Nil, sys.env).runSyncUnsafe()
    }
  }
}
