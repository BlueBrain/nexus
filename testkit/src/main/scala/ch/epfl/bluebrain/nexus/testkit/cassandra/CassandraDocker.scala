package ch.epfl.bluebrain.nexus.testkit.cassandra

import distage.{ModuleDef, TagK}
import izumi.distage.docker.ContainerDef
import izumi.distage.docker.Docker.DockerPort

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
}

class CassandraDockerModule[F[_]: TagK] extends ModuleDef {
  make[CassandraDocker.Container].fromResource {
    CassandraDocker.make[F]
  }
}

object CassandraDockerModule {
  def apply[F[_]: TagK]: CassandraDockerModule[F] = new CassandraDockerModule[F]
}
