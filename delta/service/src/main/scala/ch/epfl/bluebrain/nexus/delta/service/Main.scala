package ch.epfl.bluebrain.nexus.delta.service

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Registry
import ch.epfl.bluebrain.nexus.delta.service.plugin.{PluginConfig, PluginLoader}
import monix.bio.{IO, Task}
import monix.execution.Scheduler.Implicits.global

// Remove when start working on this project
object Main {
  def main(args: Array[String]): Unit = {

    val reg = new Registry {

      /**
        * Register a dependency.
        *
       * @param value dependency to register.
        */
      override def register[A](value: A): Task[Unit] = ???

      /**
        * Lookup a dependency.
        *
       * @return the dependency requested or error if not found.
        */
      override def lookup[A]: IO[PluginError.DependencyNotFound, A] = ???
    }
    val pl = new PluginLoader(PluginConfig(Some("./delta/test-plugin/target")))
    val _  = pl
      .loadAndStartPlugins(reg)
      .flatMap(ps =>
        IO.sequence(ps.map { p =>
          println(p.info)
          p.stop()
        })
      )
      .runSyncUnsafe()
  }
}
