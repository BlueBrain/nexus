package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{S3Storage => S3StorageType}
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Printer
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 160
}
 */
class StorageEventSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with StorageFixtures
    with TestHelpers
    with RemoteContextResolutionFixture
    with IOValues {

  "A StorageEvent" should {
    val project                   = ProjectRef.unsafe("org", "project")
    val epoch                     = Instant.EPOCH
    val subject                   = User("username", Label.unsafe("myrealm"))
    val tag                       = UserTag.unsafe("mytag")
    implicit val crypto: Crypto   = Crypto("password", "salt")
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
    val printer: Printer          = Printer.spaces2.copy(dropNullValues = true, sortKeys = true)

    "be converted to Json-LD" in {
      val list = List(
        StorageCreated(s3Id, project, s3Val, s3FieldsJson, 1, epoch, subject)                -> jsonContentOf("storage/events/storage-created.json"),
        StorageUpdated(s3Id, project, s3Val, s3FieldsJson, 2, epoch, subject)                -> jsonContentOf("storage/events/storage-updated.json"),
        StorageTagAdded(s3Id, project, S3StorageType, targetRev = 1, tag, 3, epoch, subject) -> jsonContentOf("storage/events/storage-tag-added.json"),
        StorageDeprecated(s3Id, project, S3StorageType, 4, epoch, subject)                   -> jsonContentOf("storage/events/storage-deprecated.json")
      )
      forAll(list) { case (event, json) =>
        printer.print(event.asJson) shouldEqual printer.print(json)
      }
    }
  }

}
