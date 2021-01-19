package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import com.whisk.docker.{DockerContainer, DockerReadyChecker, VolumeMapping}

import java.nio.file.{Files, Path}

trait RemoteStorageDocker extends DockerKitWithFactory {

  val remoteStorageContainer: DockerContainer = DockerContainer("bluebrain/nexus-storage:1.4.1")
    .withPorts((RemoteStorageServicePort, Some(RemoteStorageServicePort)))
    .withEnv(
      "JAVA_OPTS=-Dconfig.override_with_env_vars=true",
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
  private[remote] val RootVolume: Path              = Files.createTempDirectory("root")
  private val Bucket: Path                          = Files.createDirectories(RootVolume.resolve(s"$BucketName/nexus")).getParent
  private val my                                    = Files.createDirectory(Bucket.resolve("my"))
  private val file                                  = my.resolve("file.txt")
  private val file2                                 = my.resolve("file-2.txt")
  private val file3                                 = my.resolve("file-3.txt")
  private val file4                                 = my.resolve("file-4.txt")
  List(file, file2, file3, file4).foreach { file =>
    Files.createFile(file)
    Files.writeString(file, "file content")
  }
  val digest: ComputedDigest                        =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

}
