package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import cats.effect.IO
import cats.effect.unsafe.implicits.*
import org.scalatest.Assertions.*
import org.scalatest.{Assertion, AsyncTestSuite}

import scala.concurrent.{ExecutionContext, Future}

trait CatsEffectAsyncScalaTestAdapter extends CatsEffectAsyncScalaTestAdapterLowPrio {

  self: AsyncTestSuite =>
  implicit def ioToFutureAssertion(io: IO[Assertion]): Future[Assertion] = io.unsafeToFuture()

  implicit def futureListToFutureAssertion(future: Future[List[Assertion]]): Future[Assertion] =
    future.map(_ => succeed)
}

trait CatsEffectAsyncScalaTestAdapterLowPrio {
  implicit def ioListToFutureAssertion(io: IO[List[Assertion]])(implicit ec: ExecutionContext): Future[Assertion] =
    io.unsafeToFuture().map(_ => succeed)
}
