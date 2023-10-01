package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO
import ch.epfl.bluebrain.nexus.testkit.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.bio.{CollectionAssertions, EitherAssertions, StreamAssertions}
import monix.bio.{IO => BIO}
import monix.execution.Scheduler

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Adapted from:
  * https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/CatsEffectSuite.scala
  */
abstract class CatsEffectSuite
    extends NexusSuite
    with CatsEffectAssertions
    with StreamAssertions
    with CollectionAssertions
    with EitherAssertions
    with CatsIOValues {
  protected val ioTimeout: FiniteDuration                 = 45.seconds
  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform, munitBIOTransform)

  private val munitIOTransform: ValueTransform = {
    new ValueTransform(
      "IO",
      { case io: IO[_] =>
        io.timeout(ioTimeout).unsafeToFuture()
      }
    )
  }

  private val munitBIOTransform: ValueTransform = {
    implicit val scheduler: Scheduler = Scheduler.global
    new ValueTransform(
      "BIO",
      { case io: BIO[_, _] =>
        io.timeout(ioTimeout)
          .mapError {
            case t: Throwable => t
            case other        =>
              fail(
                s"""Error caught of type '${other.getClass.getName}', expected a successful response
                   |Error value: $other""".stripMargin
              )
          }
          .runToFuture
      }
    )
  }
}
