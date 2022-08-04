package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

import java.time.Instant
import scala.collection.immutable.VectorMap

class StorageSerializationSuite extends SerializationSuite with StorageFixtures {

  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")

  private val s3ValUpdate     = s3Val.copy(bucket = "mybucket2", maxFileSize = 41)
  private val remoteValUpdate = remoteVal.copy(folder = Label.unsafe("myfolder2"), maxFileSize = 42)

  private val storagesMapping = VectorMap(
    StorageCreated(dId, projectRef, diskVal, diskFieldsJson, 1, instant, subject)              -> loadEvents(
      "storages",
      "disk-storage-created.json"
    ),
    StorageCreated(s3Id, projectRef, s3Val, s3FieldsJson, 1, instant, subject)                 -> loadEvents(
      "storages",
      "s3-storage-created.json"
    ),
    StorageCreated(rdId, projectRef, remoteVal, remoteFieldsJson, 1, instant, subject)         -> loadEvents(
      "storages",
      "remote-storage-created.json"
    ),
    StorageUpdated(dId, projectRef, diskValUpdate, diskFieldsJson, 2, instant, subject)        -> loadEvents(
      "storages",
      "disk-storage-updated.json"
    ),
    StorageUpdated(s3Id, projectRef, s3ValUpdate, s3FieldsJson, 2, instant, subject)           -> loadEvents(
      "storages",
      "s3-storage-updated.json"
    ),
    StorageUpdated(rdId, projectRef, remoteValUpdate, remoteFieldsJson, 2, instant, subject)   -> loadEvents(
      "storages",
      "remote-storage-updated.json"
    ),
    StorageTagAdded(dId, projectRef, DiskStorageType, targetRev = 1, tag, 3, instant, subject) -> loadEvents(
      "storages",
      "storage-tag-added.json"
    ),
    StorageDeprecated(dId, projectRef, DiskStorageType, 4, instant, subject)                   -> loadEvents(
      "storages",
      "storage-deprecated.json"
    )
  )

  private val storageEventSerializer = StorageEvent.serializer(crypto)

  private val storageSseEncoder = StorageEvent.sseEncoder(crypto)

  storagesMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(storageEventSerializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(storageEventSerializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      storageSseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val statesMapping = VectorMap(
    (dId, diskVal, diskFieldsJson)  -> jsonContentOf("/storages/storage-disk-state.json"),
    (s3Id, s3Val ,s3FieldsJson)  -> jsonContentOf("/storages/storage-s3-state.json"),
    (rdId , remoteVal, remoteFieldsJson)  -> jsonContentOf("/storages/storage-remote-state.json")
  ).map { case ((id, value, source), v) =>
    StorageState(
      id,
      projectRef,
      value,
      source,
      Tags(UserTag.unsafe("mytag") -> 3),
      rev = 1,
      deprecated = false,
      createdAt = instant,
      createdBy = subject,
      updatedAt = instant,
      updatedBy = subject
    ) -> v
  }

  private val storageStateSerializer = StorageState.serializer(crypto)

  statesMapping.foreach { case (state, json) =>
    test(s"Correctly serialize state ${state.value.tpe}") {
      assertEquals(storageStateSerializer.codec(state), json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(storageStateSerializer.codec.decodeJson(json), Right(state))
    }
  }
}
