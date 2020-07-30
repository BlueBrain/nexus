package ch.epfl.bluebrain.nexus.testkit.cassandra

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import ch.epfl.bluebrain.nexus.testkit.DockerSupport
import distage.TagK
import izumi.distage.docker.Docker.DockerPort
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.docker.{ContainerDef, Docker}
import izumi.distage.model.definition.ModuleDef

object CassandraDocker extends ContainerDef {
  val primaryPort: DockerPort = DockerPort.TCP(9042)

  override def config: Config = {
    Config(
      image = "cassandra:3.11.6",
      ports = Seq(primaryPort),
      env = Map(
        "JVM_OPTS"      -> "-Xms1g -Xmx1g",
        "MAX_HEAP_SIZE" -> "1g",
        "HEAP_NEWSIZE"  -> "100m"
      )
    )
  }

  class Module[F[_]: ConcurrentEffect: ContextShift: TagK] extends ModuleDef {
    make[CassandraDocker.Container].fromResource {
      CassandraDocker.make[F]
    }

    make[CassandraHostConfig].from { docker: CassandraDocker.Container =>
      val knownAddress = docker.availablePorts.availablePorts(primaryPort).head
      CassandraHostConfig(knownAddress.hostV4, knownAddress.port)
    }

    // add docker dependencies and override default configuration
    include(new DockerSupportModule[IO] overridenBy new ModuleDef {
      make[Docker.ClientConfig].from {
        DockerSupport.clientConfig
      }
    })
  }

  final case class CassandraHostConfig(host: String, port: Int)
}

class CassandraDockerModule[F[_]: TagK] extends ModuleDef {
  make[CassandraDocker.Container].fromResource {
    CassandraDocker.make[F]
  }
}

object CassandraDockerModule {
  def apply[F[_]: TagK]: CassandraDockerModule[F] = new CassandraDockerModule[F]
}
