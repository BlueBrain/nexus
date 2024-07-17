package ch.epfl.bluebrain.nexus.testkit.file

import cats.effect.IO
import cats.effect.kernel.Resource
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.{Files, Path}
import munit.catseffect.IOFixture

object TempDirectory {

  trait Fixture { self: NexusSuite =>
    val tempDirectory: IOFixture[Path] =
      ResourceSuiteLocalFixture(
        "tempDirectory",
        Resource.make(Files[IO].createTempDirectory)(_ => IO.unit)
      )
  }

}
