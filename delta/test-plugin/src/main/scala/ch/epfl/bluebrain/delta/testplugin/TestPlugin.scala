package ch.epfl.bluebrain.delta.testplugin

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginInfo}
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

class TestPlugin(pluginDef: PluginInfo, kvStore: KVStore) extends Plugin {

  /**
    * Optional routes provided by the plugin.
    */
  override def route: Option[Route] =
    Some(
      pathPrefix("test-plugin" / Segment) { key =>
        concat(
          get { complete(kvStore.get(key).runToFuture) },
          (put & entity(as[String])) { value =>
            complete(kvStore.update(key, value).runToFuture.map(_ => "updated"))
          }
        )
      }
    )

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = Task.pure(println(s"Stopping: $pluginDef"))

  override def info: PluginInfo = pluginDef
}
