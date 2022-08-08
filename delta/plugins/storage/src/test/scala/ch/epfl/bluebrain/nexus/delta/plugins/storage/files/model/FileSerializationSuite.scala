package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class FileSerializationSuite extends SerializationSuite with StorageFixtures {

  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")

  private val storageRef = ResourceRef.Revision(iri"$dId?rev=1", dId, 1)
  private val fileId     = nxv + "file"
  private val digest     = ComputedDigest(DigestAlgorithm.default, "digest-value")
  private val uuid       = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  private val attributes =
    FileAttributes(
      uuid,
      "http://localhost/file.txt",
      Uri.Path("file.txt"),
      "file.txt",
      Some(`text/plain(UTF-8)`),
      12,
      digest,
      Client
    )

  private val filesMapping = VectorMap(
    FileCreated(
      fileId,
      projectRef,
      storageRef,
      DiskStorageType,
      attributes.copy(digest = NotComputedDigest),
      1,
      instant,
      subject
    )                                                                                                     -> loadEvents(
      "files",
      "file-created.json"
    ),
    FileUpdated(fileId, projectRef, storageRef, DiskStorageType, attributes, 2, instant, subject)         -> loadEvents(
      "files",
      "file-updated.json"
    ),
    FileAttributesUpdated(fileId, projectRef, Some(`text/plain(UTF-8)`), 12, digest, 3, instant, subject) -> loadEvents(
      "files",
      "file-attributes-created-updated.json"
    ),
    FileTagAdded(fileId, projectRef, targetRev = 1, tag, 4, instant, subject)                             -> loadEvents(
      "files",
      "file-tag-added.json"
    ),
    FileTagDeleted(fileId, projectRef, tag, 4, instant, subject)                                          -> loadEvents(
      "files",
      "file-tag-deleted.json"
    ),
    FileDeprecated(fileId, projectRef, 5, instant, subject)                                               -> loadEvents(
      "files",
      "file-deprecated.json"
    )
  )

  filesMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(FileEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(FileEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      FileEvent.sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val state = FileState(
    fileId,
    projectRef,
    storageRef,
    DiskStorageType,
    attributes,
    Tags(UserTag.unsafe("mytag") -> 3),
    5,
    false,
    instant,
    subject,
    instant,
    subject
  )

  private val jsonState = jsonContentOf("/files/database/file-state.json")

  test(s"Correctly serialize a FileState") {
    assertEquals(FileState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize a ResourceState") {
    assertEquals(FileState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
