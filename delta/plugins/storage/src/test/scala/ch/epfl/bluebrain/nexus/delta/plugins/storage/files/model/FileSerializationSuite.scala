package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.FileUserMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.{Json, JsonObject}

import java.time.Instant
import java.util.UUID

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
  private val metadata   = FileUserMetadata(Map(Label.unsafe("key") -> "value"))
    
  // format: off
  private val created = FileCreated(fileId, projectRef, storageRef, DiskStorageType, attributes.copy(digest = NotComputedDigest), Some(metadata), 1, instant, subject, None)
  private val createdTagged = created.copy(tag = Some(tag))
  private val updated = FileUpdated(fileId, projectRef, storageRef, DiskStorageType, attributes, 2, instant, subject, Some(tag))
  private val updatedAttr = FileAttributesUpdated(fileId, projectRef, storageRef, DiskStorageType, Some(`text/plain(UTF-8)`), 12, digest, 3, instant, subject)
  private val tagged = FileTagAdded(fileId, projectRef, storageRef, DiskStorageType, targetRev = 1, tag, 4, instant, subject)
  private val tagDeleted = FileTagDeleted(fileId, projectRef, storageRef, DiskStorageType, tag, 4, instant, subject)
  private val deprecated = FileDeprecated(fileId, projectRef, storageRef, DiskStorageType, 5, instant, subject)
  private val undeprecated = FileUndeprecated(fileId, projectRef, storageRef, DiskStorageType, 6, instant, subject)
  // format: on

  private def expected(event: FileEvent, newFileWritten: Json, bytes: Json, mediaType: Json, origin: Json) =
    JsonObject(
      "storage"        -> Json.fromString(event.storage.iri.toString),
      "storageType"    -> Json.fromString(event.storageType.toString),
      "newFileWritten" -> newFileWritten,
      "bytes"          -> bytes,
      "mediaType"      -> mediaType,
      "origin"         -> origin
    )

  private val filesMapping = List(
    (
      "FileCreated",
      created,
      loadEvents("files", "file-created.json"),
      Created,
      expected(created, Json.fromInt(1), Json.Null, Json.Null, Json.fromString("Client"))
    ),
    (
      "FileCreated with tags",
      createdTagged,
      loadEvents("files", "file-created-tagged.json"),
      Created,
      expected(createdTagged, Json.fromInt(1), Json.Null, Json.Null, Json.fromString("Client"))
    ),
    (
      "FileUpdated",
      updated,
      loadEvents("files", "file-updated.json"),
      Updated,
      expected(
        updated,
        Json.fromInt(1),
        Json.fromInt(12),
        Json.fromString("text/plain; charset=UTF-8"),
        Json.fromString("Client")
      )
    ),
    (
      "FileAttributesUpdated",
      updatedAttr,
      loadEvents("files", "file-attributes-created-updated.json"),
      Updated,
      expected(
        updatedAttr,
        Json.Null,
        Json.fromInt(12),
        Json.fromString("text/plain; charset=UTF-8"),
        Json.fromString("Storage")
      )
    ),
    (
      "FileTagAdded",
      tagged,
      loadEvents("files", "file-tag-added.json"),
      Tagged,
      expected(tagged, Json.Null, Json.Null, Json.Null, Json.Null)
    ),
    (
      "FileTagDeleted",
      tagDeleted,
      loadEvents("files", "file-tag-deleted.json"),
      TagDeleted,
      expected(tagDeleted, Json.Null, Json.Null, Json.Null, Json.Null)
    ),
    (
      "FileDeprecated",
      deprecated,
      loadEvents("files", "file-deprecated.json"),
      Deprecated,
      expected(deprecated, Json.Null, Json.Null, Json.Null, Json.Null)
    ),
    (
      "FileUndeprecated",
      undeprecated,
      loadEvents("files", "file-undeprecated.json"),
      Undeprecated,
      expected(undeprecated, Json.Null, Json.Null, Json.Null, Json.Null)
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
          Label.unsafe("myorg"),
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
    Some(metadata),
    Tags(UserTag.unsafe("mytag") -> 3),
    5,
    false,
    instant,
    subject,
    instant,
    subject
  )

  private val jsonState = jsonContentOf("files/database/file-state.json")

  test(s"Correctly serialize a FileState") {
    assertEquals(FileState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize a ResourceState") {
    assertEquals(FileState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
