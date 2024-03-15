package ch.epfl.bluebrain.nexus.testkit.minio

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.LocalStackV2Container
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.testcontainers.containers.localstack.LocalStackContainer.Service

object LocalStackS3 {
  val ServiceType = Service.S3

  def resource(): Resource[IO, LocalStackV2Container] = {

    def acquire: IO[LocalStackV2Container] = IO.delay {
      val container = LocalStackV2Container.Def(services = Seq(ServiceType))
      container.start()
    }

    def release(container: LocalStackV2Container): IO[Unit] = IO.delay(container.stop())

    Resource.make(acquire)(release)
  }

  trait Fixture { self: CatsEffectSuite =>
    val localStackS3: IOFixture[LocalStackV2Container] = ResourceSuiteLocalFixture("localstack", resource())
  }
}
