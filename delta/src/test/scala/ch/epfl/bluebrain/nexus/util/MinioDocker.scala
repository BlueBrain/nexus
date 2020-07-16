package ch.epfl.bluebrain.nexus.util

import distage.{ModuleDef, TagK}
import izumi.distage.docker.ContainerDef
import izumi.distage.docker.Docker.DockerPort
import izumi.distage.docker.healthcheck.ContainerHealthCheck

object MinioDocker extends ContainerDef with Randomness {
  val primaryPort: DockerPort = DockerPort.TCP(9000)
  val accessKey               = "my_key"
  val secretKey               = "my_secret_key"
  val virtualHost             = "my-domain.com"

  override def config: Config = {
    Config(
      image = "minio/minio:RELEASE.2020-07-14T19-14-30Z",
      ports = Seq(primaryPort),
      healthCheck = ContainerHealthCheck.httpGetCheck(primaryPort),
      env = Map(
        "MINIO_ACCESS_KEY"  -> accessKey,
        "MINIO_SECRET_KEY"  -> secretKey,
        "MINIO_DOMAIN"      -> virtualHost,
        "MINIO_REGION_NAME" -> "aws-global"
      ),
      cmd = List("server", sys.props.get("java.io.tmpdir").getOrElse("/tmp"))
    )
  }
}

class MinioDockerModule[F[_]: TagK] extends ModuleDef {
  make[MinioDocker.Container].fromResource {
    MinioDocker.make[F]
  }
}

object MinioDockerModule {
  def apply[F[_]: TagK]: MinioDockerModule[F] = new MinioDockerModule[F]
}
