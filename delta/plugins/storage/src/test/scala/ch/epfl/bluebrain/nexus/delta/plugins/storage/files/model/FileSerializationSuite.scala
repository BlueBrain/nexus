package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.DiskStorage as DiskStorageType
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.*
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef, Tags}
import io.circe.JsonObject
import io.circe.syntax.KeyOps
import org.http4s.Uri

import java.time.Instant
import java.util.UUID

class FileSerializationSuite extends SerializationSuite with StorageFixtures {

  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")

  private val storageRef  = ResourceRef.Revision(iri"$dId?rev=1", dId, 1)
  private val fileId      = nxv + "file"
  private val digest      = ComputedDigest(DigestAlgorithm.default, "digest-value")
  private val uuid        = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  private val keywords    = Map(Label.unsafe("key") -> "value")
  private val description = "A description"
  private val name        = "A name"
  private val attributes  =
    FileAttributes(
      uuid,
      Uri.unsafeFromString("http://localhost/file.txt"),
      Uri.Path.unsafeFromString("file.txt"),
      "file.txt",
      Some(MediaType.`text/plain`),
      Map.empty,
      None,
      None,
      12,
      digest,
      Client
    )

  private val attributesWithMetadata =
    attributes.copy(keywords = keywords, description = Some(description), name = Some(name))
  private val customMetadata         =
    FileCustomMetadata(Some(name), Some(description), Some(keywords))

  // format: off
  private val created = FileCreated(fileId, projectRef, storageRef, DiskStorageType, attributes.copy(digest = NotComputedDigest), 1, instant, subject, None)
  private val createdWithMetadata = FileCreated(fileId, projectRef, storageRef, DiskStorageType, attributesWithMetadata.copy(digest = NotComputedDigest), 1, instant, subject, None)
  private val createdTagged = created.copy(tag = Some(tag))
  private val createdTaggedWithMetadata = createdWithMetadata.copy(tag = Some(tag))
  private val updated = FileUpdated(fileId, projectRef, storageRef, DiskStorageType, attributes, 2, instant, subject, Some(tag))
  private val updatedAttr = FileAttributesUpdated(fileId, projectRef, storageRef, DiskStorageType, Some(MediaType.`text/plain`), 12, digest, 3, instant, subject)
  private val updatedMetadata = FileCustomMetadataUpdated(fileId, projectRef, storageRef, DiskStorageType, customMetadata, 3, instant, subject, Some(tag))
  private val tagged = FileTagAdded(fileId, projectRef, storageRef, DiskStorageType, targetRev = 1, tag, 4, instant, subject)
  private val tagDeleted = FileTagDeleted(fileId, projectRef, storageRef, DiskStorageType, tag, 4, instant, subject)
  private val deprecated = FileDeprecated(fileId, projectRef, storageRef, DiskStorageType, 5, instant, subject)
  private val undeprecated = FileUndeprecated(fileId, projectRef, storageRef, DiskStorageType, 6, instant, subject)
  // format: on

  private val clientOrigin  = Some(FileAttributesOrigin.Client)
  private val storageOrigin = Some(FileAttributesOrigin.Storage)
  private val textMediaType = Some(MediaType.`text/plain`)

  private def expectedExtraFields(
      event: FileEvent,
      newFileWritten: Option[Int],
      bytes: Option[Long],
      mediaType: Option[MediaType],
      extension: Option[String],
      origin: Option[FileAttributesOrigin]
  )        =
    JsonObject(
      "storage"        := event.storage.iri,
      "storageType"    := event.storageType,
      "newFileWritten" := newFileWritten,
      "bytes"          := bytes,
      "mediaType"      := mediaType,
      "extension"      := extension,
      "origin"         := origin
    )

  private val filesMapping = List(
    (
      "FileCreated",
      created,
      loadEvents("files", "file-created.json"),
      Set(Created),
      expectedExtraFields(created, Some(1), None, None, None, clientOrigin)
    ),
    (
      "FileCreated with metadata",
      createdWithMetadata,
      loadEvents("files", "file-created-with-metadata.json"),
      Set(Created),
      expectedExtraFields(created, Some(1), None, None, None, clientOrigin)
    ),
    (
      "FileCreated with tags",
      createdTagged,
      loadEvents("files", "file-created-tagged.json"),
      Set(Created, Tagged),
      expectedExtraFields(createdTagged, Some(1), None, None, None, clientOrigin)
    ),
    (
      "FileCreated with tags and keywords",
      createdTaggedWithMetadata,
      loadEvents("files", "file-created-tagged-with-metadata.json"),
      Set(Created, Tagged),
      expectedExtraFields(createdTaggedWithMetadata, Some(1), None, None, None, clientOrigin)
    ),
    (
      "FileUpdated",
      updated,
      loadEvents("files", "file-updated.json"),
      Set(Updated, Tagged),
      expectedExtraFields(updated, Some(1), Some(12), textMediaType, Some("txt"), clientOrigin)
    ),
    (
      "FileAttributesUpdated",
      updatedAttr,
      loadEvents("files", "file-attributes-created-updated.json"),
      Set(Updated),
      expectedExtraFields(updatedAttr, None, Some(12), textMediaType, None, storageOrigin)
    ),
    (
      "FileCustomMetadataUpdated",
      updatedMetadata,
      loadEvents("files", "file-custom-metadata-updated.json"),
      Set(Updated),
      expectedExtraFields(updatedMetadata, None, None, None, None, None)
    ),
    (
      "FileTagAdded",
      tagged,
      loadEvents("files", "file-tag-added.json"),
      Set(Tagged),
      expectedExtraFields(tagged, None, None, None, None, None)
    ),
    (
      "FileTagDeleted",
      tagDeleted,
      loadEvents("files", "file-tag-deleted.json"),
      Set(TagDeleted),
      expectedExtraFields(tagDeleted, None, None, None, None, None)
    ),
    (
      "FileDeprecated",
      deprecated,
      loadEvents("files", "file-deprecated.json"),
      Set(Deprecated),
      expectedExtraFields(deprecated, None, None, None, None, None)
    ),
    (
      "FileUndeprecated",
      undeprecated,
      loadEvents("files", "file-undeprecated.json"),
      Set(Undeprecated),
      expectedExtraFields(undeprecated, None, None, None, None, None)
    )
  )

  filesMapping.foreach { case (name, event, (database, sse), action, expectedExtraFields) =>
    test(s"Correctly serialize $name") {
      assertEquals(FileEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize $name") {
      assertEquals(FileEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize $name as an SSE") {
      FileEvent.sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }

    test(s"Correctly encode $name to metric") {
      FileEvent.fileEventMetricEncoder.toMetric.decodeJson(database).assertRight {
        ProjectScopedMetric(
          instant,
          subject,
          event.rev,
          action,
          projectRef,
          event.id,
          Set(nxvFile),
          expectedExtraFields
        )
      }
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

  private val stateWithMetadata = state.copy(attributes = attributesWithMetadata)

  private val fileState             = jsonContentOf("files/database/file-state.json")
  private val fileStateWithMetadata = jsonContentOf("files/database/file-state-with-metadata.json")

  test(s"Correctly serialize a FileState") {
    assertEquals(FileState.serializer.codec(state), fileState)
  }

  test(s"Correctly deserialize a FileState") {
    assertEquals(FileState.serializer.codec.decodeJson(fileState), Right(state))
  }

  test(s"Correctly serialize a FileState with metadata") {
    assertEquals(FileState.serializer.codec(stateWithMetadata), fileStateWithMetadata)
  }

  test(s"Correctly deserialize a FileState with metadata") {
    assertEquals(FileState.serializer.codec.decodeJson(fileStateWithMetadata), Right(stateWithMetadata))
  }

}
