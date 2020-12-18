package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
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

  val RemoteStorageServicePort: Int    = 8080
  val BucketName: Label                = Label.unsafe("nexustest")
  private[remote] val RootVolume: Path = Files.createTempDirectory("root")
  private val Bucket: Path             = Files.createDirectories(RootVolume.resolve(s"$BucketName/nexus")).getParent
  private val my                       = Files.createDirectory(Bucket.resolve("my"))
  private val file                     = my.resolve("file.txt")
  Files.createFile(file)
  Files.writeString(file, "file content")
}
