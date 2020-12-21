package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import scala.collection.immutable.VectorMap

class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with StorageFixtures
    with CancelAfterFailure {

  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = TagLabel.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")

  private val s3ValUpdate     = s3Val.copy(bucket = "mybucket2", maxFileSize = 41)
  private val remoteValUpdate = remoteVal.copy(folder = Label.unsafe("myfolder2"), maxFileSize = 42)

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
  // format: on

  "An EventSerializer" should behave like eventToJsonSerializer("storage", storagesMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("storage", storagesMapping)

}
