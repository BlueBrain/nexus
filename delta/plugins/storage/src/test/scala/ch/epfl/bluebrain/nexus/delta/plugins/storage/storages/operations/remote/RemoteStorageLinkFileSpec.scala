package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import java.util.UUID

@DoNotDiscover
class RemoteStorageLinkFileSpec(fixture: RemoteStorageClientFixtures)
    extends TestKit(ActorSystem("RemoteStorageMoveFileSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with StorageFixtures
    with BeforeAndAfterAll
    with ConfigFixtures {

  private lazy val remoteDiskStorageClient = fixture.init

  private val iri                   = iri"http://localhost/remote"
  private val uuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidf: UUIDF = UUIDF.fixed(uuid)
  private val project               = ProjectRef.unsafe("org", "project")
  private val filename              = "file-2.txt"

  private var storageValue: RemoteDiskStorageValue = _
  private var storage: RemoteDiskStorage           = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    storageValue = RemoteDiskStorageValue(
      default = true,
      DigestAlgorithm.default,
      Label.unsafe(RemoteStorageClientFixtures.BucketName),
      read,
      write,
      10
    )
    storage = RemoteDiskStorage(iri, project, storageValue, Json.obj())
  }

  "RemoteDiskStorage linking operations" should {

    "succeed" in {
      storage.linkFile(remoteDiskStorageClient).apply(Uri.Path("my/file-2.txt"), filename).accepted shouldEqual
        FileStorageMetadata(
          uuid,
          12,
          NotComputedDigest,
          Storage,
          s"file:///app/${RemoteStorageClientFixtures.BucketName}/nexus/org/project/8/0/4/9/b/a/9/0/file-2.txt",
          Uri.Path("org/project/8/0/4/9/b/a/9/0/file-2.txt")
        )
    }

    "fail linking a file that does not exist" in {
      storage
        .linkFile(remoteDiskStorageClient)
        .apply(Uri.Path("my/file-40.txt"), filename)
        .rejectedWith[FileNotFound]
    }
  }
}
