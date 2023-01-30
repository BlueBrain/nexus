package ch.epfl.bluebrain.nexus.testkit.minio

import ch.epfl.bluebrain.nexus.testkit.minio.MinioContainer.Version
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class MinioContainer(
    rootUser: String,
    rootPassword: String,
    virtualHost: String,
    region: String
) extends GenericContainer[MinioContainer](DockerImageName.parse(s"minio/minio:$Version")) {

  addEnv("MINIO_ROOT_USER", rootUser)
  addEnv("MINIO_ROOT_PASSWORD", rootPassword)
  addEnv("MINIO_DOMAIN", virtualHost)
  addEnv("MINIO_REGION_NAME", region)
  setCommand("server", sys.props.getOrElse("java.io.tmpdir", "/tmp"))
  addExposedPort(9000)
  setWaitStrategy(Wait.forHttp("/minio/health/live"))
}

object MinioContainer {
  private val Version: String = "RELEASE.2022-08-02T23-59-16Z"
}
