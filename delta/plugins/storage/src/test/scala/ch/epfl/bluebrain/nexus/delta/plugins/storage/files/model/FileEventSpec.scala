package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileTagAdded, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Printer
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 200
}
 */
class FileEventSpec extends AnyWordSpecLike with Matchers with Inspectors with StorageFixtures with TestHelpers with RemoteContextResolutionFixture with IOValues {

  "A FileEvent" should {
    val project                   = ProjectRef.unsafe("org", "project")
    val epoch                     = Instant.EPOCH
    val subject                   = User("username", Label.unsafe("myrealm"))
    val tag                       = TagLabel.unsafe("mytag")
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
    val storageRef                = ResourceRef.Revision(iri"$dId?rev=1", dId, 1)
    val fileId                    = nxv + "file"
    val digest                    = ComputedDigest(DigestAlgorithm.default, "digest-value")
    val uuid                      = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val attributes =
      FileAttributes(uuid, "http://localhost/file.txt", Uri.Path("file.txt"), "file.txt", `text/plain(UTF-8)`, 12, digest, Client)

    val printer: Printer = Printer.spaces2.copy(dropNullValues = true, sortKeys = true)

    "be converted to Json-LD" in {
      val list = List(
        FileCreated(fileId, project, storageRef, DiskStorageType, attributes.copy(digest = NotComputedDigest), 1, epoch, subject) -> jsonContentOf("file/events/file-created.json"),
        FileUpdated(fileId, project, storageRef, DiskStorageType, attributes, 2, epoch, subject)                                  -> jsonContentOf("file/events/file-updated.json"),
        FileAttributesUpdated(fileId, project, `text/plain(UTF-8)`, 12, digest, 3, epoch, subject)                                -> jsonContentOf("file/events/file-attr-updated.json"),
        FileTagAdded(fileId, project, targetRev = 1, tag, 4, epoch, subject)                                                      -> jsonContentOf("file/events/file-tag-added.json"),
        FileDeprecated(fileId, project, 5, epoch, subject)                                                                        -> jsonContentOf("file/events/file-deprecated.json")
      )
      forAll(list) { case (event, json) =>
        printer.print(event.toCompactedJsonLd.accepted.json) shouldEqual printer.print(json)
      }
    }
  }

}
