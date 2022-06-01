package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.{ContentTypes, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, Permissions}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class FileEventExchangeSpec
    extends AbstractDBSpec
    with TryValues
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with FileFixtures
    with RemoteContextResolutionFixture {

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext         = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val cfg = config.copy(
    disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path)
  )

  private val aclSetup = AclSetup.init(
    (
      subject,
      AclAddress.Root,
      Set(Permissions.resources.read, diskFields.readPermission.value, diskFields.writePermission.value)
    )
  )

  private val (files, storages) = FilesSetup.init(org, project, aclSetup.accepted, cfg)
  private val storageJson       = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
  storages.create(diskId, projectRef, storageJson).accepted

  private def fileAttributes(bytes: Long, withDigest: Boolean) =
    FileAttributes(
      UUID.randomUUID(),
      s"file:///something",
      Uri.Path("org/something"),
      "something.txt",
      Some(ContentTypes.`application/json`),
      bytes,
      if (withDigest) ComputedDigest(DigestAlgorithm.default, "value") else NotComputedDigest,
      Storage
    )

  "A FileEventExchange" should {
    val id     = iri"http://localhost/${genString()}"
    val tag    = UserTag.unsafe("tag")
    val source =
      json"""{
               "_uuid" : "8249ba90-7cc6-4de5-93a1-802c04200dcc",
               "_filename" : "file.txt",
               "_mediaType" : "text/plain; charset=UTF-8",
               "_bytes" : 12,
               "_digest" : {
                 "_algorithm" : "SHA-256",
                 "_value" : "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c"
               },
               "_origin" : "Client",
               "_storage" : {
                 "@id" : "https://bluebrain.github.io/nexus/vocabulary/disk",
                 "@type" : "https://bluebrain.github.io/nexus/vocabulary/DiskStorage",
                 "_rev" : 1
               }
             }"""

    val exchange = new FileEventExchange(files)

    val resRev1         = files.create(id, Some(diskId), project.ref, entity()).accepted
    val resRev2         = files.tag(id, project.ref, tag, 1L, 1L).accepted
    val deprecatedEvent = FileDeprecated(id, project.ref, 1, Instant.EPOCH, subject)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value.asInstanceOf[EventExchangeValue[_, _]]
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev2
      result.metadata.value shouldEqual resRev2.value
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value.asInstanceOf[EventExchangeValue[_, _]]
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev1
      result.metadata.value shouldEqual resRev1.value
    }
    "return TagNotFound when the file is not found by tag" in {
      exchange
        .toResource(deprecatedEvent, Some(UserTag.unsafe("unknown")))
        .accepted
        .value
        .asInstanceOf[EventExchange.TagNotFound]
        .id shouldEqual deprecatedEvent.id
    }

    "return the matching metric for a create event with a digest" in {
      val event = FileCreated(
        id,
        project.ref,
        ResourceRef.Revision(diskId, 1),
        StorageType.DiskStorage,
        fileAttributes(10L, withDigest = true),
        1L,
        Instant.EPOCH,
        subject
      )

      exchange.toMetric(event).accepted.value shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Created,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxvFile),
        JsonObject(
          "storage"   -> diskId.asJson,
          "bytes"     -> 10.asJson,
          "mediaType" -> "application/json".asJson,
          "origin"    -> "Storage".asJson
        )
      )
    }

    "return the matching metric for a create event without a digest" in {
      val event = FileCreated(
        id,
        project.ref,
        ResourceRef.Revision(diskId, 1),
        StorageType.DiskStorage,
        fileAttributes(10L, withDigest = false),
        1L,
        Instant.EPOCH,
        subject
      )

      exchange.toMetric(event).accepted.value shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Created,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxvFile),
        JsonObject(
          "storage"   -> diskId.asJson,
          "bytes"     -> Json.Null,
          "mediaType" -> Json.Null,
          "origin"    -> "Storage".asJson
        )
      )
    }

    "return the matching metric for an update event" in {
      val event = FileUpdated(
        id,
        project.ref,
        ResourceRef.Revision(diskId, 1),
        StorageType.DiskStorage,
        fileAttributes(10L, withDigest = true),
        1L,
        Instant.EPOCH,
        subject
      )

      exchange.toMetric(event).accepted.value shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Updated,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxvFile),
        JsonObject(
          "storage"   -> diskId.asJson,
          "bytes"     -> 10.asJson,
          "mediaType" -> "application/json".asJson,
          "origin"    -> "Storage".asJson
        )
      )
    }

    "return the matching metric for an update event without a digest" in {
      val event = FileUpdated(
        id,
        project.ref,
        ResourceRef.Revision(diskId, 1),
        StorageType.DiskStorage,
        fileAttributes(10L, withDigest = false),
        1L,
        Instant.EPOCH,
        subject
      )

      exchange.toMetric(event).accepted.value shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Updated,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxvFile),
        JsonObject(
          "storage"   -> diskId.asJson,
          "bytes"     -> Json.Null,
          "mediaType" -> Json.Null,
          "origin"    -> "Storage".asJson
        )
      )
    }

    "return the matching metric for an file attributes updated event" in {
      val event = FileAttributesUpdated(
        id,
        project.ref,
        Some(ContentTypes.`application/json`),
        25L,
        NotComputedDigest,
        1L,
        Instant.EPOCH,
        subject
      )

      exchange.toMetric(event).accepted.value shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        FileEventExchange.AttributesUpdated,
        project.ref,
        project.organizationLabel,
        id,
        Set(nxvFile),
        JsonObject(
          "storage"   -> diskId.asJson,
          "bytes"     -> 25.asJson,
          "mediaType" -> "application/json".asJson,
          "origin"    -> "Storage".asJson
        )
      )
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : ["${Vocabulary.contexts.metadata}", "${contexts.files}"],
          "@type" : "FileDeprecated",
          "_fileId" : "$id",
          "_resourceId" : "$id",
          "_project" : "http://localhost/v1/projects/org/proj",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
