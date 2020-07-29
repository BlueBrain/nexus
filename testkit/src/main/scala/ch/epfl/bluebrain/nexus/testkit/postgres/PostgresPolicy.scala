package ch.epfl.bluebrain.nexus.testkit.postgres

import cats.effect.{IO, Timer}
import cats.implicits._
import doobie.util.transactor.Transactor

import scala.concurrent.duration.FiniteDuration

import scala.concurrent.duration._

object PostgresPolicy {

  def waitForPostgresReady(xa: Transactor[IO], maxDelay: FiniteDuration = 30.seconds)
                          (implicit tm: Timer[IO]): IO[Unit] = {
    import doobie.implicits._
    import retry.CatsEffect._
    import retry.RetryPolicies._
    import retry._
    val policy = limitRetriesByCumulativeDelay[IO](maxDelay, constantDelay(1.second))
    retryingOnAllErrors(
      policy = policy,
      onError = (_: Throwable, _) => IO.delay(println("Postgres Container not ready, retrying..."))
    ) {
      sql"select 1;".query[Int].unique.transact(xa)
    } *> IO.unit
  }

}
