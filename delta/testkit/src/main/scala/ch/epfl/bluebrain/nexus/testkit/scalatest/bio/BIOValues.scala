package ch.epfl.bluebrain.nexus.testkit.scalatest.bio

import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.{Assertion, Assertions, Suite}

import scala.concurrent.Future

trait BIOValues extends BIOValuesLowPrio {
  self: Suite =>

  implicit def taskToFutureAssertion(
      task: Task[Assertion]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    task.runToFuture

  implicit def futureListToFutureAssertion(
      future: Future[List[Assertion]]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    future.map(_ => succeed)
}

trait BIOValuesLowPrio extends Assertions {
  implicit def taskListToFutureAssertion(
      task: Task[List[Assertion]]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    task.runToFuture.map(_ => succeed)
}
