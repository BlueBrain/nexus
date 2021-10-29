package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithTimeouts
import com.whisk.docker.{DockerContainer, DockerReadyChecker, VolumeMapping}

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration._

trait RemoteStorageDocker extends DockerKitWithTimeouts {

  override val StartContainersTimeout: FiniteDuration = 40.seconds

  val remoteStorageContainer: DockerContainer = DockerContainer("bluebrain/nexus-storage:1.5.1")
    .withPorts((RemoteStorageServicePort, Some(RemoteStorageServicePort)))
    .withEnv(
      "JAVA_OPTS=-Xmx256m -Dconfig.override_with_env_vars=true",
      "CONFIG_FORCE_app_subject_anonymous=true",
      "CONFIG_FORCE_app_instance_interface=0.0.0.0",
      "CONFIG_FORCE_app_storage_root__volume=/app"
    )
    .withVolumes(List(VolumeMapping(RootVolume.toString, "/app", rw = true)))
    .withReadyChecker(
      DockerReadyChecker.LogLineContains(s"Bound to 0.0.0.0: $RemoteStorageServicePort")
    )

  abstract override def dockerContainers: List[DockerContainer] =
    remoteStorageContainer :: super.dockerContainers
}

object RemoteStorageDocker {

  private[remote] val RemoteStorageServicePort: Int = 8080
  val RemoteStorageEndpoint: BaseUri                = BaseUri(s"http://localhost:$RemoteStorageServicePort", Label.unsafe("v1"))
  val BucketName: Label                             = Label.unsafe("nexustest")

  private val rwx = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))

  private[remote] val RootVolume = Files.createTempDirectory("root", rwx)
  private val bucket             = Files.createDirectory(RootVolume.resolve(BucketName.value), rwx)
  private val bucketNexus        = Files.createDirectory(bucket.resolve("nexus"), rwx)
  private val my                 = Files.createDirectory(bucket.resolve("my"), rwx)

  (1 to 4).map(idx => s"file-$idx.txt").foreach { fileName =>
    val path = Files.createFile(my.resolve(fileName), rwx)
    path.toFile.setWritable(true, false)
    Files.writeString(path, "file content")
  }
  List(bucket, bucketNexus, my).foreach { path =>
    path.toFile.setWritable(true, false)
  }

  val digest: ComputedDigest =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

}
