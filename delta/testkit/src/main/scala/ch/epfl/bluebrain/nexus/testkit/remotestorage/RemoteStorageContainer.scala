package ch.epfl.bluebrain.nexus.testkit.remotestorage

import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{BindMode, GenericContainer}
import org.testcontainers.utility.DockerImageName

import java.nio.file.Path

class RemoteStorageContainer(rootVolume: Path)
    extends GenericContainer[RemoteStorageContainer](DockerImageName.parse("bluebrain/nexus-storage:1.7.0")) {

  addEnv("JAVA_OPTS", "-Xmx256m -Dconfig.override_with_env_vars=true")
  addEnv("CONFIG_FORCE_app_subject_anonymous", "true")
  addEnv("CONFIG_FORCE_app_instance_interface", "0.0.0.0")
  addEnv("CONFIG_FORCE_app_storage_root__volume", "/app")
  addFileSystemBind(rootVolume.toString, "/app", BindMode.READ_WRITE)
  addExposedPort(8080)
  setWaitStrategy(Wait.forLogMessage(".*Bound\\sto\\s0\\.0\\.0\\.0.*", 1))
}
