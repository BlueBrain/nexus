package ch.epfl.bluebrain.nexus.cli.influx

import distage.TagK
import izumi.distage.docker.Docker.DockerReusePolicy.ReuseEnabled
import izumi.distage.docker.Docker.{ContainerConfig, DockerPort}
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.docker.{ContainerDef, Docker}
import izumi.distage.model.definition.ModuleDef
import org.http4s.Uri

object InfluxDocker extends ContainerDef {

  val primaryPort: DockerPort = DockerPort.TCP(8086)

  override def config: InfluxDocker.Config =
    ContainerConfig(
      image = "library/influxdb:1.8.6",
      ports = Seq(primaryPort),
      env = Map("INFLUXDB_REPORTING_DISABLED" -> "true", "INFLUXDB_HTTP_FLUX_ENABLED" -> "true"),
      reuse = ReuseEnabled
    )

  class Module[F[_]: TagK] extends ModuleDef {
    make[InfluxDocker.Container].fromResource {
      InfluxDocker.make[F]
    }

    make[InfluxHostConfig].from { docker: InfluxDocker.Container =>
      val knownAddress = docker.availablePorts.first(primaryPort)
      InfluxHostConfig(knownAddress.hostV4, knownAddress.port)
    }

    // add docker dependencies and override default configuration
    include(new DockerSupportModule[F] overriddenBy new ModuleDef {
      make[Docker.ClientConfig].from {
        Docker.ClientConfig(
          readTimeoutMs = 60000, // long timeout for gh actions
          connectTimeoutMs = 500,
          globalReuse = ReuseEnabled,
          useRemote = false,
          useRegistry = true,
          remote = None,
          registry = None
        )
      }
    })
  }

  final case class InfluxHostConfig(host: String, port: Int) {
    def endpoint: Uri = Uri.unsafeFromString(s"http://$host:$port")
  }
}
