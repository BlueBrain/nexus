package ch.epfl.bluebrain.nexus.delta.testplugin

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
      make[TestPluginRoutes].from { (permissions: Permissions, scheduler: Scheduler) =>
        implicit val sc = scheduler
        new TestPluginRoutes(permissions)
      }
      many[PriorityRoute].add((routes: TestPluginRoutes) =>
        PriorityRoute(1, routes.routes, requiresStrictEntity = true)
      )
    }

  override val info: PluginDescription = PluginDescription(Name.unsafe("testplugin"), "0.1.0")

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(locator.get[TestPlugin])

}
