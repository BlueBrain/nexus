package ch.epfl.bluebrain.nexus.testkit.bio

import monix.bio.IO
import monix.execution.Scheduler
import munit.FunSuite

abstract class BioSuite
    extends FunSuite
    with BioFixtures
    with BioFunFixtures
    with BioAssertions
    with StreamAssertions
    with CollectionAssertions
    with EitherAssertions {

  implicit protected val scheduler: Scheduler     = Scheduler.global
  implicit protected val classLoader: ClassLoader = getClass.getClassLoader

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  private val munitIOTransform: ValueTransform =
    new ValueTransform(
      "IO",
      { case io: IO[_, _] =>
        io.mapError {
          case t: Throwable => t
          case other        =>
            fail(
              s"""Error caught of type '${other.getClass.getName}', expected a successful response
                 |Error value: $other""".stripMargin
            )
        }.runToFuture
      }
    )
}
