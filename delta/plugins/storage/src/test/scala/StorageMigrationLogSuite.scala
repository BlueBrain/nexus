import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{storages, StoragePluginModule}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class StorageMigrationLogSuite extends BioSuite with StorageFixtures {

  private val instant    = Instant.EPOCH
  private val subject    = User("username", Label.unsafe("myrealm"))
  private val tag        = UserTag.unsafe("mytag")
  private val projectRef = ProjectRef.unsafe("myorg", "myproj")

  private val s3ValUpdate     = s3Val.copy(bucket = "mybucket2", maxFileSize = 41)
  private val remoteValUpdate = remoteVal.copy(folder = Label.unsafe("myfolder2"), maxFileSize = 42)

  private val diskCreated = (id: Iri) => StorageCreated(id, projectRef, diskVal, diskFieldsJson, 1, instant, subject)
  private val diskUpdated = (id: Iri) =>
    StorageUpdated(id, projectRef, diskValUpdate, diskFieldsJson, 2, instant, subject)

  private val defaultStorageId = storages.defaultStorageId
  private val s3Created        = StorageCreated(s3Id, projectRef, s3Val, s3FieldsJson, 1, instant, subject)
  private val remoteCreated    = StorageCreated(rdId, projectRef, remoteVal, remoteFieldsJson, 1, instant, subject)
  private val s3Updated        = StorageUpdated(s3Id, projectRef, s3ValUpdate, s3FieldsJson, 2, instant, subject)
  private val remoteUpdated    = StorageUpdated(rdId, projectRef, remoteValUpdate, remoteFieldsJson, 2, instant, subject)
  private val diskTagged       =
    StorageTagAdded(defaultStorageId, projectRef, DiskStorage, targetRev = 1, tag, 3, instant, subject)
  private val diskDeprecated   = StorageDeprecated(defaultStorageId, projectRef, DiskStorage, 4, instant, subject)

  private val defaults  = Defaults("storageName", "storageDescription")
  private val injection = StoragePluginModule.injectStorageDefaults(defaults)

  test("Storage events that need no name/desc injection should stay untouched") {
    val events         = List(s3Created, remoteCreated, s3Updated, remoteUpdated, diskTagged, diskDeprecated)
    val injectedEvents = events.map(injection)
    assertEquals(injectedEvents, events)
  }

  test("Storage event should not be injected with a name if it's not the default view") {
    val storageCreatedEvent = diskCreated(iri"someId")
    val storageUpdatedEvent = diskUpdated(iri"someId")
    val events              = List(storageCreatedEvent, storageUpdatedEvent)
    val injectedEvents      = events.map(injection)
    assertEquals(injectedEvents, events)
  }

  private val expectedStorageValueCreated =
    diskVal.copy(name = Some(defaults.name), description = Some(defaults.description))
  private val expectedStorageValueUpdated =
    diskValUpdate.copy(name = Some(defaults.name), description = Some(defaults.description))

  test("Default StorageCreated has name/desc injected") {
    val injectedEvent = injection(diskCreated(defaultStorageId))
    val expected      =
      StorageCreated(defaultStorageId, projectRef, expectedStorageValueCreated, diskFieldsJson, 1, instant, subject)
    assertEquals(injectedEvent, expected)
  }

  test("Default StorageUpdated has name/desc injected") {
    val injectedEvent = injection(diskUpdated(defaultStorageId))
    val expected      =
      StorageUpdated(defaultStorageId, projectRef, expectedStorageValueUpdated, diskFieldsJson, 2, instant, subject)
    assertEquals(injectedEvent, expected)
  }

}
