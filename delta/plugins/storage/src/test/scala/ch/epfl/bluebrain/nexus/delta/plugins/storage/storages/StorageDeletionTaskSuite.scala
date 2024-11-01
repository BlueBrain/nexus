package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream
import munit.AnyFixture

class StorageDeletionTaskSuite extends NexusSuite with StorageFixtures with ActorSystemSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(actorSystem)
  implicit private lazy val as: ActorSystem      = actorSystem()
  implicit private val uuidf: UUIDF              = UUIDF.random

  test("Delete content from local storage") {
    val diskOps                                 = DiskFileOperations.mk
    implicit val subject: Subject               = Anonymous
    val project                                 = ProjectRef.unsafe("org", "proj")
    val content                                 = "file content"
    val entity                                  = HttpEntity(content)
    val uploading                               = DiskUploadingFile(project, diskVal.volume, DigestAlgorithm.default, "trace", entity)
    val storageStream: Stream[IO, StorageValue] = Stream(diskVal, s3Val, remoteVal)
    val storageDir                              = diskVal.rootDirectory(project)

    def fileExists(metadata: FileStorageMetadata) = diskOps.fetch(metadata.location.path).redeem(_ => false, _ => true)

    for {
      metadata    <- diskOps.save(uploading)
      _           <- fileExists(metadata).assertEquals(true, s"'${metadata.location}' should have been created.")
      deletionTask = new StorageDeletionTask(_ => storageStream)
      result      <- deletionTask(project)
      _            = assertEquals(result.log.size, 3, s"The three storages should have been processed:\n$result")
      _            = fileExists(metadata).assertEquals(false, s"'${metadata.location}' should have been deleted.")
      _            = assert(!storageDir.exists, s"The directory '$storageDir' should have been deleted.")

    } yield ()
  }
}
