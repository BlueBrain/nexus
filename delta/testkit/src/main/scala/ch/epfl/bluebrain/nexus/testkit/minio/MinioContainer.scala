package ch.epfl.bluebrain.nexus.testkit.minio

import ch.epfl.bluebrain.nexus.testkit.minio.MinioContainer.Version
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class MinioContainer(
    accessKey: String,
    secretKey: String,
    virtualHost: String,
    region: String
) extends GenericContainer[MinioContainer](DockerImageName.parse(s"minio/minio:$Version")) {

  addEnv("MINIO_ACCESS_KEY", accessKey)
  addEnv("MINIO_SECRET_KEY", secretKey)
  addEnv("MINIO_DOMAIN", virtualHost)
  addEnv("MINIO_REGION_NAME", region)
  setCommand("server", sys.props.getOrElse("java.io.tmpdir", "/tmp"))
  addExposedPort(9000)
  setWaitStrategy(Wait.forHttp("/").forStatusCode(403))
}

object MinioContainer {
  private val Version: String = "RELEASE.2021-07-30T00-02-00Z"
}
