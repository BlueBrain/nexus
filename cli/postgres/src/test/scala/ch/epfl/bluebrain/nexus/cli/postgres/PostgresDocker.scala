package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import ch.epfl.bluebrain.nexus.cli.postgres.config.PostgresConfig
import distage.TagK
import izumi.distage.docker.Docker.{ContainerConfig, DockerPort}
import izumi.distage.docker.modules.DockerContainerModule
import izumi.distage.docker.{ContainerDef, Docker}
import izumi.distage.model.definition.ModuleDef

object PostgresDocker extends ContainerDef {

  val primaryPort: DockerPort = DockerPort.TCP(5432)

  override def config: PostgresDocker.Config =
    ContainerConfig(
      image = "library/postgres:12.2",
      ports = Seq(primaryPort),
      env = Map("POSTGRES_PASSWORD" -> "postgres"),
      reuse = true
    )

  class Module[F[_]: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
    make[PostgresDocker.Container].fromResource {
      PostgresDocker.make[F]
    }

    make[PostgresConfig].from { docker: PostgresDocker.Container =>
      val knownAddress = docker.availablePorts(primaryPort).head
      PostgresConfig(knownAddress.hostV4, knownAddress.port)
    }

    // add docker dependencies and override default configuration
    include(new DockerContainerModule[F] overridenBy new ModuleDef {
      make[Docker.ClientConfig].from {
        Docker.ClientConfig(
          readTimeoutMs = 1500,
          connectTimeoutMs = 1500,
          allowReuse = true,
          useRemote = false,
          useRegistry = true,
          remote = None,
          registry = None
        )
      }
    })
  }
}
