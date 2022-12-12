package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage, MigrationStorage}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

import java.time.Instant
import java.util.UUID

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
    val storageCreatedEvent = diskCreated(dId)
    val storageUpdatedEvent = diskUpdated(dId)
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

  private val storageRef = ResourceRef.Revision(iri"migration", dId, 1)
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

  // format: off
  private val created = FileCreated(fileId, projectRef, storageRef, DiskStorage, attributes.copy(digest = NotComputedDigest), 1, instant, subject)
  private val updated = FileUpdated(fileId, projectRef, storageRef, DiskStorage, attributes, 2, instant, subject)
  private val updatedAttr = FileAttributesUpdated(fileId, projectRef, storageRef, MigrationStorage, Some(`text/plain(UTF-8)`), 12, digest, 3, instant, subject)
  private val tagged = FileTagAdded(fileId, projectRef, storageRef, MigrationStorage, targetRev = 1, tag, 4, instant, subject)
  private val tagDeleted = FileTagDeleted(fileId, projectRef, storageRef, MigrationStorage, tag, 4, instant, subject)
  private val deprecated = FileDeprecated(fileId, projectRef, storageRef, MigrationStorage, 5, instant, subject)
  // format: on

  private val fileInjection   = StoragePluginModule.injectFileStorageInfo
  private val enrichFileEvent = StoragePluginModule.enrichJsonFileEvent

  test("An empty JSON has a storage and storageType added") {
    val enrichedJson = enrichFileEvent(JsonObject.empty.asJson)
    val expected     = JsonObject(
      "storage"     -> Json.fromString("https://bluebrain.github.io/nexus/vocabulary/migration-storage?rev=1"),
      "storageType" -> Json.fromString("MigrationStorage")
    )
    assertEquals(enrichedJson, expected.asJson)
  }

  test("If storage/storageType fields already exist, they are not enriched") {
    val jsonObject   = JsonObject(
      "storage"     -> Json.fromString("remoteStorageRef"),
      "storageType" -> Json.fromString("RemoteStorage")
    )
    val enrichedJson = enrichFileEvent(jsonObject.asJson)
    assertEquals(enrichedJson, jsonObject.asJson)
  }

  test("If there is no state, there is no storage injection in files") {
    val allEvents      = List(created, updated, updatedAttr, tagged, tagDeleted, deprecated)
    val injectedEvents = allEvents.map(event => fileInjection(event, None))
    assertEquals(injectedEvents, allEvents)
  }

  test("Apply the storage/storageType from FileState") {
    val storageRef    = ResourceRef.Revision(iri"$dId?rev=1", dId, 1)
    val fileState     = FileState(
      iri"someId",
      projectRef,
      storageRef,
      DiskStorage,
      attributes,
      Tags.empty,
      1,
      false,
      instant,
      subject,
      instant,
      subject
    )
    val injectedEvent = fileInjection(tagged, Some(fileState))
    val expected      = FileTagAdded(fileId, projectRef, storageRef, DiskStorage, targetRev = 1, tag, 4, instant, subject)
    assertEquals(injectedEvent, expected)
  }

}
