package ch.epfl.bluebrain.nexus.delta.sourcing

import monix.bio.IO
import monix.execution.Scheduler
import munit.FunSuite

abstract class MonixBioSuite extends FunSuite with MonixBioAssertions with StreamAssertions {

  implicit def s: Scheduler = Scheduler.global

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
