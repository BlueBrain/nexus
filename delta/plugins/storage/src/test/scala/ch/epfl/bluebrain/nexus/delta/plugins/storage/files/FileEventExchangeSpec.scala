package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.FileDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup}
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import java.time.Instant
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

  "A FileEventExchange" should {
    val id     = iri"http://localhost/${genString()}"
    val tag    = TagLabel.unsafe("tag")
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
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev2
      result.metadata.value shouldEqual resRev2.value
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.source shouldEqual source
      result.value.resource shouldEqual resRev1
      result.metadata.value shouldEqual resRev1.value
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
          "_project" : "org/proj",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
