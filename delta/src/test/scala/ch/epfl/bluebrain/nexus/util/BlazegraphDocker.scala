package ch.epfl.bluebrain.nexus.util

import distage.{ModuleDef, TagK}
import izumi.distage.docker.ContainerDef
import izumi.distage.docker.Docker.DockerPort

object BlazegraphDocker extends ContainerDef {
  val primaryPort: DockerPort = DockerPort.TCP(9999)

  override def config: Config = {
    Config(
      image = "bluebrain/blazegraph-nexus:2.1.5",
      ports = Seq(primaryPort)
    )
  }
}

class BlazegraphDockerModule[F[_]: TagK] extends ModuleDef {
  make[BlazegraphDocker.Container].fromResource {
    BlazegraphDocker.make[F]
  }
}

object BlazegraphDockerModule {
  def apply[F[_]: TagK]: BlazegraphDockerModule[F] = new BlazegraphDockerModule[F]
}
