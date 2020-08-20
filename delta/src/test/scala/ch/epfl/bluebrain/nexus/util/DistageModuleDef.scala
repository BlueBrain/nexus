package ch.epfl.bluebrain.nexus.util

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.{ContextShift, IO, Resource}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import distage.ModuleDef
import izumi.distage.docker.Docker
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.effect.modules.CatsDIEffectModule

abstract class DistageModuleDef(name: String)(implicit context: ContextShift[IO]) extends ModuleDef {
  // add docker dependencies and override default configuration
  include(new DockerSupportModule[IO] overridenBy new ModuleDef {
    make[Docker.ClientConfig].from {
      Docker.ClientConfig(
        readTimeoutMs = 60000, // long timeout for gh actions
        connectTimeoutMs = 500,
        allowReuse = true,
        useRemote = false,
        useRegistry = true,
        remote = None,
        registry = None
      )
    }
  })
  include(CatsDIEffectModule)

  make[ActorSystem].fromResource {
    val acquire = for {
      cfg <- IO(ConfigFactory.load("test.conf").withFallback(ConfigFactory.load()))
      as  <- IO(ActorSystem(name, cfg))
    } yield as
    val release = (system: ActorSystem) => IO.fromFuture(IO(system.terminate())) >> IO.unit
    Resource.make(acquire)(release)
  }

  make[Materializer].from { as: ActorSystem =>
    SystemMaterializer(as).materializer
  }
}
