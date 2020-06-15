package ch.epfl.bluebrain.nexus.cli.literature

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.TagK
import izumi.distage.docker.Docker.{ContainerConfig, DockerPort}
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.docker.{ContainerDef, Docker}
import izumi.distage.model.definition.ModuleDef
import org.http4s.Uri

object ElasticSearchDocker extends ContainerDef {

  val primaryPort: DockerPort = DockerPort.TCP(9200)

  override def config: ElasticSearchDocker.Config =
    ContainerConfig(
      image = "library/elasticsearch:7:6:2",
      ports = Seq(primaryPort),
      env = Map("discovery.type" -> "single-node"),
      reuse = true
    )

  class Module[F[_]: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
    make[ElasticSearchDocker.Container].fromResource {
      ElasticSearchDocker.make[F]
    }

    make[ElasticSearchHostConfig].from { docker: ElasticSearchDocker.Container =>
      val knownAddress = docker.availablePorts.availablePorts(primaryPort).head
      ElasticSearchHostConfig(knownAddress.hostV4, knownAddress.port)
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

  final case class ElasticSearchHostConfig(host: String, port: Int) {
    def endpoint: Uri = Uri.unsafeFromString(s"http://$host:$port")
  }
}
