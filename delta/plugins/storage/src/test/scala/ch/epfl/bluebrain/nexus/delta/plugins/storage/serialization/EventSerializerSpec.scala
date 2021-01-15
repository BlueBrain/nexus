package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileTagAdded, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with StorageFixtures
    with CancelAfterFailure {

  override val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = TagLabel.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")

  private val s3ValUpdate     = s3Val.copy(bucket = "mybucket2", maxFileSize = 41)
  private val remoteValUpdate = remoteVal.copy(folder = Label.unsafe("myfolder2"), maxFileSize = 42)
  private val storageRef      = ResourceRef.Revision(iri"$dId?rev=1", dId, 1)
  private val fileId          = nxv + "file"
  private val digest          = ComputedDigest(DigestAlgorithm.default, "digest-value")
  private val uuid            = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  private val attributes      =
    FileAttributes(
      uuid,
      "http://localhost/file.txt",
      Uri.Path("file.txt"),
      "file.txt",
      `text/plain(UTF-8)`,
      12,
      digest,
      Client
    )

  // format: off
  val storagesMapping: Map[StorageEvent, Json] = VectorMap(
    StorageCreated(dId, projectRef, diskVal, diskFieldsJson, 1, instant, subject)             -> jsonContentOf("/storage/serialization/disk-storage-created.json"),
    StorageCreated(s3Id, projectRef, s3Val, s3FieldsJson, 1, instant, subject)                -> jsonContentOf("/storage/serialization/s3-storage-created.json"),
    StorageCreated(rdId, projectRef, remoteVal, remoteFieldsJson, 1, instant, subject)        -> jsonContentOf("/storage/serialization/remote-storage-created.json"),
    StorageUpdated(dId, projectRef, diskValUpdate, diskFieldsJson, 2, instant, subject)       -> jsonContentOf("/storage/serialization/disk-storage-updated.json"),
    StorageUpdated(s3Id, projectRef, s3ValUpdate, s3FieldsJson, 2, instant, subject)          -> jsonContentOf("/storage/serialization/s3-storage-updated.json"),
    StorageUpdated(rdId, projectRef, remoteValUpdate, remoteFieldsJson, 2, instant, subject)  -> jsonContentOf("/storage/serialization/remote-storage-updated.json"),
    StorageTagAdded(dId, projectRef, targetRev = 1, tag, 3, instant, subject)                                                         -> jsonContentOf("/storage/serialization/storage-tag-added.json"),
    StorageDeprecated(dId, projectRef, 4, instant, subject)                                                                           -> jsonContentOf("/storage/serialization/storage-deprecated.json")
  )

  val filesMapping: Map[FileEvent, Json] = VectorMap(
    FileCreated(fileId, projectRef, storageRef, DiskStorageType, attributes.copy(digest = NotComputedDigest), 1, instant, subject) -> jsonContentOf("/file/serialization/file-created.json"),
    FileUpdated(fileId, projectRef, storageRef, DiskStorageType, attributes, 2, instant, subject)                                  -> jsonContentOf("/file/serialization/file-updated.json"),
    FileAttributesUpdated(fileId, projectRef, `text/plain(UTF-8)`, 12, digest, 3, instant, subject)                                -> jsonContentOf("/file/serialization/file-attributes-created-updated.json"),
    FileTagAdded(fileId, projectRef, targetRev = 1, tag, 4, instant, subject)                                                      -> jsonContentOf("/file/serialization/file-tag-added.json"),
    FileDeprecated(fileId, projectRef, 5, instant, subject)                                                                        -> jsonContentOf("/file/serialization/file-deprecated.json")
  )
  // format: on

  "An EventSerializer" should behave like eventToJsonSerializer("storage", storagesMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("storage", storagesMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("file", filesMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("file", filesMapping)

}
