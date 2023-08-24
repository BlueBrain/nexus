package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.testkit.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.bio.{CollectionAssertions, EitherAssertions, StreamAssertions}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

abstract class CatsEffectSuite
    extends NexusSuite
    with CatsEffectAssertions
    with StreamAssertions
    with CollectionAssertions
    with EitherAssertions {

  implicit protected val classLoader: ClassLoader       = getClass.getClassLoader
  implicit protected val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

  protected val ioTimeout: FiniteDuration = 45.seconds

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  private val munitIOTransform: ValueTransform =
    new ValueTransform(
      "IO",
      { case io: IO[_] => io.timeout(ioTimeout).unsafeToFuture() }
    )
}
