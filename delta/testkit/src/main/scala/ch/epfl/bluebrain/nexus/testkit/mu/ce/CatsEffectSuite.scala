package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.IO
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.{CollectionAssertions, EitherAssertions, EitherValues, NexusSuite}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Adapted from:
  * https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/CatsEffectSuite.scala
  */
abstract class CatsEffectSuite
    extends NexusSuite
    with CatsRunContext
    with CatsIOValues
    with CatsEffectAssertions
    with CatsStreamAssertions
    with CollectionAssertions
    with EitherAssertions
    with EitherValues
    with FixedClock {
  protected val ioTimeout: FiniteDuration = 45.seconds

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  private val munitIOTransform: ValueTransform = {
    new ValueTransform(
      "IO",
      { case io: IO[_] =>
        io.timeout(ioTimeout).unsafeToFuture()
      }
    )
  }
}
