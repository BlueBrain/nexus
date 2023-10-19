package ch.epfl.bluebrain.nexus.testkit.bio

import ch.epfl.bluebrain.nexus.testkit.{EitherValuesMUnit, IOFixedClock, IOValuesMUnit, NexusSuite}
import monix.bio.IO
import monix.execution.Scheduler

import scala.concurrent.duration.{DurationInt, FiniteDuration}

abstract class BioSuite
    extends NexusSuite
    with BioFixtures
    with BioFunFixtures
    with BioAssertions
    with IOValuesMUnit
    with StreamAssertions
    with CollectionAssertions
    with EitherAssertions
    with EitherValuesMUnit
    with IOFixedClock {

  implicit protected val scheduler: Scheduler = Scheduler.global

  protected val ioTimeout: FiniteDuration = 45.seconds

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  private val munitIOTransform: ValueTransform =
    new ValueTransform(
      "IO",
      { case io: IO[_, _] =>
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
