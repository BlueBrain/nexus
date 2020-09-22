package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import ch.epfl.bluebrain.nexus.testkit.DockerSupport
import distage.TagK
import izumi.distage.docker.Docker.{ContainerConfig, DockerPort}
import izumi.distage.docker.healthcheck.ContainerHealthCheck
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
      reuse = true,
      healthCheck = ContainerHealthCheck.postgreSqlProtocolCheck(
        primaryPort,
        "postgres",
        "postgres"
      )
    )

  class Module[F[_]: ConcurrentEffect: ContextShift: TagK] extends ModuleDef {
    make[PostgresDocker.Container].fromResource {
      PostgresDocker.make[F]
    }

    make[PostgresHostConfig].from { docker: PostgresDocker.Container =>
      val knownAddress = docker.availablePorts.first(primaryPort)
      PostgresHostConfig(knownAddress.hostV4, knownAddress.port)
    }

    // add docker dependencies and override default configuration
    include(new DockerSupportModule[IO] overridenBy new ModuleDef {
      make[Docker.ClientConfig].from {
        DockerSupport.clientConfig
      }
    })
  }

  final case class PostgresHostConfig(host: String, port: Int)
}
