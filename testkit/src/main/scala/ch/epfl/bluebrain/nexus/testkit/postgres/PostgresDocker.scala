package ch.epfl.bluebrain.nexus.testkit.postgres

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.TagK
import izumi.distage.docker.Docker.{ContainerConfig, DockerPort}
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.docker.{ContainerDef, Docker}
import izumi.distage.model.definition.ModuleDef

object PostgresDocker extends ContainerDef {

  val primaryPort: DockerPort = DockerPort.TCP(5432)
  val password: String        = "postgres"

  override def config: PostgresDocker.Config =
    ContainerConfig(
      image = "library/postgres:12.2",
      ports = Seq(primaryPort),
      env = Map("POSTGRES_PASSWORD" -> password),
      reuse = true
    )

  class Module[F[_]: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
    make[PostgresDocker.Container].fromResource {
      PostgresDocker.make[F]
    }

    make[PostgresHostConfig].from { docker: PostgresDocker.Container =>
      val knownAddress = docker.availablePorts.availablePorts(primaryPort).head
      PostgresHostConfig(knownAddress.hostV4, knownAddress.port)
    }

    // add docker dependencies and override default configuration
    include(new DockerSupportModule[F] overridenBy new ModuleDef {
      make[Docker.ClientConfig].from {
        Docker.ClientConfig(
          readTimeoutMs = 60000, // long timeout for gh actions
          connectTimeoutMs = 500,
          allowReuse = false,
          useRemote = false,
          useRegistry = true,
          remote = None,
          registry = None
        )
      }
    })
  }

  final case class PostgresHostConfig(host: String, port: Int)
}
