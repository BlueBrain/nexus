package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import scala.concurrent.ExecutionContext

class FileReferenceExchangeSpec
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

  "A FileReferenceExchange" should {
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

    val exchange = Files.referenceExchange(files)

    val resRev1 = files.create(id, Some(diskId), project.ref, entity()).accepted
    val resRev2 = files.tag(id, project.ref, tag, 1L, 1L).accepted

    "return a file by id" in {
      val value = exchange.fetch(project.ref, Latest(id)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev2
    }

    "return a file by tag" in {
      val value = exchange.fetch(project.ref, Tag(id, tag)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return a file by rev" in {
      val value = exchange.fetch(project.ref, Revision(id, 1L)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return None for incorrect id" in {
      exchange.fetch(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.fetch(project.ref, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = UserTag.unsafe("unknown")
      exchange.fetch(project.ref, Tag(id, label)).accepted shouldEqual None
    }
  }
}
