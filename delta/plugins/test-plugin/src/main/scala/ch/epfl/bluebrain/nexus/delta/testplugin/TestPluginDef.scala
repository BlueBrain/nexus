package ch.epfl.bluebrain.nexus.delta.testplugin

import akka.http.scaladsl.server.Directives.{complete, concat, get, pathPrefix}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PriorityRoute}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task
import monix.execution.Scheduler

case class TestPluginDef() extends PluginDef {

  override def module: ModuleDef =
    new ModuleDef {
      make[TestPlugin]
      make[PriorityRoute].from { (permission: Permissions, scheduler: Scheduler) =>
        implicit val sc = scheduler
        val route       = pathPrefix("test-plugin") {
          concat(
            get {
              complete(permission.fetchPermissionSet.map(ps => s"${ps.mkString(",")}").runToFuture)
            }
          )
        }
        PriorityRoute(1, route)
      }
    }

  override val info: PluginDescription = PluginDescription(Name.unsafe("testplugin"), "0.1.0")

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(locator.get[TestPlugin])

}
