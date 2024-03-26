package ch.epfl.bluebrain.nexus.testkit.minio

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.LocalStackV2Container
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import software.amazon.awssdk.services.s3.S3AsyncClient

object LocalStackS3 {
  val ServiceType = Service.S3

  def resource(): Resource[IO, LocalStackV2Container] = {

    def acquire: IO[LocalStackV2Container] = IO.delay {
      val containerDef = LocalStackV2Container.Def(services = Seq(ServiceType))
      containerDef.start()
    }

    def release(container: LocalStackV2Container): IO[Unit] = IO.delay(container.stop())

    Resource.make(acquire)(release)
  }

  def fs2ClientResource(): Resource[IO, S3AsyncClientOp[IO]] = resource().flatMap { container =>
    val endpoint = container.endpointOverride(LocalStackS3.ServiceType)

    Interpreter[IO].S3AsyncClientOpResource(
      S3AsyncClient
        .builder()
        .credentialsProvider(container.staticCredentialsProvider)
        .endpointOverride(endpoint)
        .forcePathStyle(true)
        .region(container.region)
    )
  }

  trait Fixture { self: CatsEffectSuite =>
    val localStackS3: IOFixture[LocalStackV2Container]     = ResourceSuiteLocalFixture("localstack", resource())
    val localStackS3Client: IOFixture[S3AsyncClientOp[IO]] = ResourceSuiteLocalFixture("s3client", fs2ClientResource())
  }
}
