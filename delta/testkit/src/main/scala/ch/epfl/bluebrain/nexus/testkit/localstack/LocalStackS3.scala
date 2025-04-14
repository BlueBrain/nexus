package ch.epfl.bluebrain.nexus.testkit.localstack

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.LocalStackV2Container
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import software.amazon.awssdk.services.s3.S3AsyncClient

object LocalStackS3 {
  val ServiceType = Service.S3
  val Version     = "4.3"

  def localstackS3(): Resource[IO, LocalStackV2Container] = {

    def acquire: IO[LocalStackV2Container] = IO.delay {
      val containerDef = LocalStackV2Container.Def(tag = Version, services = Seq(ServiceType))
      containerDef.start()
    }

    def release(container: LocalStackV2Container): IO[Unit] = IO.delay(container.stop())

    Resource.make(acquire)(release)
  }

  def fs2ClientFromLocalstack(localstack: LocalStackV2Container): Resource[IO, S3AsyncClientOp[IO]] = {
    val endpoint = localstack.endpointOverride(LocalStackS3.ServiceType)

    Interpreter[IO].S3AsyncClientOpResource(
      S3AsyncClient
        .builder()
        .credentialsProvider(localstack.staticCredentialsProvider)
        .endpointOverride(endpoint)
        .forcePathStyle(true)
        .region(localstack.region)
    )
  }

  def fs2Client(): Resource[IO, S3AsyncClientOp[IO]] = localstackS3().flatMap(fs2ClientFromLocalstack)

  trait Fixture { self: CatsEffectSuite =>
    val localStackS3Client: IOFixture[S3AsyncClientOp[IO]] = ResourceSuiteLocalFixture("s3client", fs2Client())
  }
}
