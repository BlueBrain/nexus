package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient.uploadFileToS3
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{iriStringContextSyntax, uriStringContextSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigSuite.{defaultBgValues, defaultEsValues}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.Path
import munit.AnyFixture

import java.net.URI
import scala.concurrent.duration.{Duration, DurationInt}

class ShipConfigSuite extends NexusSuite with ShipConfigFixtures with LocalStackS3StorageClient.Fixture {

  override def munitIOTimeout: Duration = 60.seconds

  private lazy val (s3Client, fs2S3client, _) = localStackS3Client()

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)
  private val bucket                             = "bucket"

  test("Default configuration should be parsed and loaded") {
    val expectedBaseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))
    ShipConfig.load(None).map(_.input.targetBaseUri).assertEquals(expectedBaseUri)
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
      config             <- ShipConfig.load(Some(Path(externalConfigPath)))
    } yield {
      assertEquals(config.input.files.locationPrefixToStrip, Some(uri"""file:///prefix/to/strip"""))
      assertEquals(config.input.targetBaseUri, expectedBaseUri)
    }
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
    val importBucket     = "my-import-bucket"
    val expectedEndpoint = new URI("http://my-s3-endpoint.com")
    for {
      externalConfigPath <- loader.absolutePath("config/s3.conf")
      s3Config           <- ShipConfig.load(Some(Path(externalConfigPath))).map(_.s3)
      _                   = assertEquals(s3Config.endpoint, expectedEndpoint)
      _                   = assertEquals(s3Config.importBucket, importBucket)
    } yield ()
  }

  test("Should read the import bucket") {
    for {
      config <- ShipConfig.load(None).map(_.input)
      _       = assertEquals(config.files.importBucket, inputConfig.files.importBucket)
    } yield ()
  }

  test("Should read the target bucket") {
    for {
      config <- ShipConfig.load(None).map(_.input)
      _       = assertEquals(config.files.targetBucket, inputConfig.files.targetBucket)
    } yield ()
  }

  test("Should read the resource types to ignore") {
    for {
      externalConfigPath <- loader.absolutePath("config/external.conf")
      config             <- ShipConfig.load(Some(Path(externalConfigPath)))
      _                   = assertEquals(config.input.resourceTypesToIgnore, Set(iri"https://some.resource.type"))
    } yield ()
  }

  test("Should read the amazon storage config") {
    for {
      amazonConfig <- ShipConfig
                        .load(None)
                        .map(_.input.storages.storageTypeConfig.amazon)
      _             = assertEquals(amazonConfig, inputConfig.storages.storageTypeConfig.amazon)
    } yield ()
  }

  test("Succeed in overloading the default config with an external config in S3") {
    val configPath = Path("/config/external.conf")
    for {
      _          <- uploadFileToS3(fs2S3client, bucket, configPath)
      shipConfig <- ShipConfig.loadFromS3(s3Client, bucket, configPath)
      _           = assertEquals(shipConfig.input.targetBaseUri.toString, "https://bbp.epfl.ch/v1")
    } yield ()
  }

}

object ShipConfigSuite {
  private val defaultEsValues =
    Defaults("Default Elasticsearch view", "An Elasticsearch view of all resources in the project.")
  private val defaultBgValues = Defaults("Default Sparql view", "A Sparql view of all resources in the project.")
}
