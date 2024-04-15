package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import java.util.UUID

@DoNotDiscover
class RemoteStorageSaveAndFetchFileSpec(fixture: RemoteStorageClientFixtures)
    extends TestKit(ActorSystem("RemoteStorageSaveAndFetchFileSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with Eventually
    with BeforeAndAfterAll
    with StorageFixtures {

  private lazy val remoteDiskStorageClient = fixture.init

  private val iri                   = iri"http://localhost/remote"
  private val uuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidf: UUIDF = UUIDF.fixed(uuid)
  private val project               = ProjectRef.unsafe("org", "project")
  private val filename              = "myfile.txt"

  private val storageValue: RemoteDiskStorageValue = RemoteDiskStorageValue(
    default = true,
    DigestAlgorithm.default,
    Label.unsafe(RemoteStorageClientFixtures.BucketName),
    read,
    write,
    10
  )
  private val storage: RemoteDiskStorage           = RemoteDiskStorage(iri, project, storageValue, Json.obj())
  private val saveFile                             = new RemoteDiskStorageSaveFile(remoteDiskStorageClient)
  private val fetchAttr                            = new RemoteStorageFetchAttributes(remoteDiskStorageClient)

  "RemoteDiskStorage operations" should {
    val content = "file content"
    val entity  = HttpEntity(content)

    val bytes    = 12L
    val digest   = ComputedDigest(DigestAlgorithm.default, RemoteStorageClientFixtures.Digest)
    val location = s"file:///app/${RemoteStorageClientFixtures.BucketName}/nexus/org/project/8/0/4/9/b/a/9/0/myfile.txt"
    val path     = Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt")

    "save a file to a folder" in {
      saveFile.apply(storage, filename, entity).accepted shouldEqual FileStorageMetadata(
        uuid,
        bytes,
        digest,
        Client,
        location,
        path
      )
    }

    "fetch a file from a folder" in {
      val sourceFetched = remoteDiskStorageClient.getFile(storage.value.folder, path).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fetch a file attributes" in eventually {
      val computedAttributes = fetchAttr.apply(storage, path).accepted
      computedAttributes.digest shouldEqual digest
      computedAttributes.bytes shouldEqual bytes
      computedAttributes.mediaType shouldEqual `text/plain(UTF-8)`
    }

    "fail attempting to save the same file again" in {
      saveFile.apply(storage, filename, entity).rejectedWith[ResourceAlreadyExists]
    }
  }
}
