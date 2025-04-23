package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.UUIDFFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileDataHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.http4s.Uri
import org.scalatest.BeforeAndAfterAll

import java.nio.file.{Files, Paths}
import scala.reflect.io.Directory

class DiskStorageSaveFileSpec
    extends CatsEffectSpec
    with FileDataHelpers
    with UUIDFFixtures.Fixed
    with BeforeAndAfterAll {

  private val volume  = AbsolutePath(Files.createTempDirectory("disk-access")).rightValue
  private val file    = AbsolutePath(Paths.get(s"$volume/org/project/8/0/4/9/b/a/9/0/myfile.txt")).rightValue
  private val fileOps = DiskFileOperations.mk

  "A DiskStorage saving operations" should {
    val project = ProjectRef.unsafe("org", "project")
    val content = "file content"
    val digest  = "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c"
    val data    = streamData(content)

    val uploading = DiskUploadingFile(project, volume, DigestAlgorithm.default, "myfile.txt", data)

    "save a file to a volume" in {

      val metadata = fileOps.save(uploading).accepted

      Files.readString(file.value) shouldEqual content

      metadata shouldEqual
        FileStorageMetadata(
          fixedUuid,
          Files.size(file.value),
          ComputedDigest(DigestAlgorithm.default, digest),
          Client,
          Uri.unsafeFromString(s"file://$file"),
          Uri.Path.unsafeFromString("org/project/8/0/4/9/b/a/9/0/myfile.txt")
        )

      consume(fileOps.fetch(metadata.location.path)).accepted shouldEqual content
    }

    "fail attempting to save the same file again" in {
      fileOps.save(uploading).rejectedWith[ResourceAlreadyExists]
    }
  }

  override protected def afterAll(): Unit = {
    Directory(volume.value.toFile).deleteRecursively()
    ()
  }
}
