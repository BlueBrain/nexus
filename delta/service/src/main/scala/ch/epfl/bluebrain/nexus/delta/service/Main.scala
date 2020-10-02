package ch.epfl.bluebrain.nexus.delta.service

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Registry
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult
import ch.epfl.bluebrain.nexus.delta.service.plugin.{PluginConfig, PluginLoader}
import monix.bio.{IO, Task}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

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

    val pl      = new PluginLoader(PluginConfig(Some("./delta/test-plugin/target")))
    val plugins = pl
      .loadAndStartPlugins(reg)
      .runSyncUnsafe()
    val routes  = plugins.flatMap(_.route).reduce(concat(_, _))

    implicit val as = ActorSystem()

    val logger = Logging(as, getClass)

    val httpBinding = {
      Http()
        .newServerAt("127.0.0.1", 8080)
        .bindFlow(
          RouteResult.routeToFlow(
            routes
          )
        )
    }
    httpBinding onComplete {
      case Success(binding) =>
        logger.info(s"Bound to ${binding.localAddress.getHostString}: ${binding.localAddress.getPort}")
      case Failure(th)      =>
        logger.error(
          th,
          "Failed to perform an http binding on {}:{}",
          "127.0.0.1",
          8080
        )
        Await.result(as.terminate(), 1.minute)
    }

  }
}
