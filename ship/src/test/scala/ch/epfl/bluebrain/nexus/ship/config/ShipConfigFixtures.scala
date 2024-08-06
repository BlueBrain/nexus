package ch.epfl.bluebrain.nexus.ship.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{permissions, StorageFixtures, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, Defaults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ClasspathResources

trait ShipConfigFixtures extends ConfigFixtures with StorageFixtures with ClasspathResources {

  private val baseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))

  private val organizationsCreation = OrganizationCreationConfig(
    Map(Label.unsafe("public") -> "The public organization", Label.unsafe("obp") -> "The OBP organization")
  )

  private val viewDefaults = ViewDefaults(
    Defaults("Default ES View", "Description ES View"),
    Defaults("Default EBG View", "Description BG View")
  )

  private val serviceAccount: ServiceAccountConfig = ServiceAccountConfig(
    ServiceAccount(User("internal", Label.unsafe("sa")))
  )

  private val importBucket = "nexus-ship-production"
  private val targetBucket = "nexus-delta-production"

  private val amazonConfig: S3StorageConfig =
    S3StorageConfig(
      "https://s3.us-east-1.amazonaws.com",
      useDefaultCredentialProvider = false,
      Secret("my-key"),
      Secret("my-secret-key"),
      permissions.read,
      files.permissions.write,
      showLocation = true,
      10737418240L,
      defaultBucket = targetBucket,
      prefix = None
    )

  def inputConfig: InputConfig =
    InputConfig(
      baseUri,
      baseUri,
      eventLogConfig,
      organizationsCreation,
      Map.empty,
      viewDefaults,
      serviceAccount,
      StoragesConfig(eventLogConfig, pagination, config.copy(amazon = Some(amazonConfig))),
      FileProcessingConfig(
        importBucket,
        targetBucket,
        Some(Uri.Path("/prefix")),
        skipFileEvents = false
      ),
      IriPatcherConfig(enabled = false, iri"https://bbp.epfl.ch/", iri"https:/openbrainplatform.com/"),
      Set.empty
    )

}
