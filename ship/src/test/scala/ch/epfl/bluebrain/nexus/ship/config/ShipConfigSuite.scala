package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import eu.timepit.refined.types.string.NonEmptyString
import fs2.aws.s3.models.Models.BucketName
import fs2.io.file.Path

import java.net.URI

class ShipConfigSuite extends NexusSuite {

  test("Default configuration should be parsed and loaded") {
    val expectedBaseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))
    ShipConfig.load(None).map(_.baseUri).assertEquals(expectedBaseUri)
  }

  test("Default configuration should be overloaded by the external config") {
    val expectedBaseUri = BaseUri("https://bbp.epfl.ch", Label.unsafe("v1"))
    for {
      externalConfigPath <- loader.absolutePath("config/external.conf")
      _                  <- ShipConfig.load(Some(Path(externalConfigPath))).map(_.baseUri).assertEquals(expectedBaseUri)
    } yield ()
  }

  test("Should have correct project mapping") {
    val privateMmb   = ProjectRef.unsafe("private", "mmb")
    val obpReference = ProjectRef.unsafe("obp", "reference")
    val expected     = Map(privateMmb -> obpReference)

    for {
      externalConfigPath <- loader.absolutePath("config/project-mapping.conf")
      mapping             = ShipConfig.load(Some(Path(externalConfigPath))).map(_.projectMapping)
      _                  <- mapping.assertEquals(expected)
    } yield ()
  }

  test("Should read the S3 config") {
    val bucket = BucketName(NonEmptyString.unsafeFrom("my-import-bucket"))
    val expected = S3Config(new URI("http://my-s3-endpoint.com"), bucket)
    for {
      externalConfigPath <- loader.absolutePath("config/s3.conf")
      s3Config            = ShipConfig.load(Some(Path(externalConfigPath))).map(_.S3)
      _                  <- s3Config.assertEquals(expected)
    } yield ()
  }

}
