package ch.epfl.bluebrain.nexus.util

import distage.{ModuleDef, TagK}
import izumi.distage.docker.ContainerDef
import izumi.distage.docker.Docker.DockerPort
import izumi.distage.docker.healthcheck.ContainerHealthCheck

object ElasticSearchDocker extends ContainerDef {
  val primaryPort: DockerPort = DockerPort.TCP(9200)

  override def config: Config = {
    Config(
      image = "docker.elastic.co/elasticsearch/elasticsearch:7.5.1",
      ports = Seq(primaryPort),
      env = Map(
        "discovery.type" -> "single-node"
      ),
      healthCheck = ContainerHealthCheck.httpGetCheck(primaryPort)
    )
  }
}

class ElasticSearchDockerModule[F[_]: TagK] extends ModuleDef {
  make[ElasticSearchDocker.Container].fromResource {
    ElasticSearchDocker.make[F]
  }
}

object ElasticSearchDockerModule {
  def apply[F[_]: TagK]: ElasticSearchDockerModule[F] = new ElasticSearchDockerModule[F]
}
