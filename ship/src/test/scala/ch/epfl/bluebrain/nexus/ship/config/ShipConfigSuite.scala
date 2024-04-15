package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient.uploadFileToS3
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigSuite.{defaultBgValues, defaultEsValues}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import eu.timepit.refined.types.string.NonEmptyString
import fs2.aws.s3.models.Models.BucketName
import fs2.io.file.Path
import munit.AnyFixture

import java.net.URI

class ShipConfigSuite extends NexusSuite with LocalStackS3StorageClient.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)

  private lazy val (s3Client, fs2S3client, _) = localStackS3Client()
  private val bucket                          = BucketName(NonEmptyString.unsafeFrom("bucket"))

  test("Default configuration should be parsed and loaded") {
    val expectedBaseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))
    ShipConfig.load(None).map(_.input.baseUri).assertEquals(expectedBaseUri)
  }

  test("The defaults (name/description) for views should be correct") {
    val config = ShipConfig.load(None)
    config.map(_.input.viewDefaults.elasticsearch).assertEquals(defaultEsValues) >>
      config.map(_.input.viewDefaults.blazegraph).assertEquals(defaultBgValues)
  }

  test("Default configuration should be overloaded by the external config") {
    val expectedBaseUri = BaseUri("https://bbp.epfl.ch", Label.unsafe("v1"))
    for {
      externalConfigPath <- loader.absolutePath("config/external.conf")
      _                  <- ShipConfig.load(Some(Path(externalConfigPath))).map(_.input.baseUri).assertEquals(expectedBaseUri)
    } yield ()
  }

  test("Should have correct project mapping") {
    val privateMmb   = ProjectRef.unsafe("private", "mmb")
    val obpReference = ProjectRef.unsafe("obp", "reference")
    val expected     = Map(privateMmb -> obpReference)

    for {
      externalConfigPath <- loader.absolutePath("config/project-mapping.conf")
      mapping             = ShipConfig.load(Some(Path(externalConfigPath))).map(_.input.projectMapping)
      _                  <- mapping.assertEquals(expected)
    } yield ()
  }

  test("Should read the S3 config") {
    val bucket   = BucketName(NonEmptyString.unsafeFrom("my-import-bucket"))
    val expected = S3Config(new URI("http://my-s3-endpoint.com"), bucket)
    for {
      externalConfigPath <- loader.absolutePath("config/s3.conf")
      s3Config            = ShipConfig.load(Some(Path(externalConfigPath))).map(_.s3)
      _                  <- s3Config.assertEquals(expected)
    } yield ()
  }

  test("Succeed in overloading the default config with an external config in S3") {
    val configPath = Path("/config/external.conf")
    for {
      _          <- uploadFileToS3(fs2S3client, bucket, configPath)
      shipConfig <- ShipConfig.loadFromS3(s3Client, bucket, configPath)
      _           = assertEquals(shipConfig.input.baseUri.toString, "https://bbp.epfl.ch/v1")
    } yield ()
  }

}

object ShipConfigSuite {
  private val defaultEsValues =
    Defaults("Default Elasticsearch view", "An Elasticsearch view of all resources in the project.")
  private val defaultBgValues = Defaults("Default Sparql view", "A Sparql view of all resources in the project.")
}
