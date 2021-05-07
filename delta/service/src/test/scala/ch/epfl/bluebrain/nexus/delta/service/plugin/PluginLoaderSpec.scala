package ch.epfl.bluebrain.nexus.delta.service.plugin

import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PriorityRoute}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PluginLoaderSpec extends AnyWordSpecLike with ScalatestRouteTest with Matchers with IOValues {

  private val perms         = PermissionsDummy(Set(Permission.unsafe("test"), Permission.unsafe("test2")))
  private val serviceModule = new ModuleDef {
    make[Permissions].fromEffect(perms)
    make[Scheduler].from(Scheduler.global)
  }

  "A PluginLoader" should {
    val config = PluginLoaderConfig("../plugins/test-plugin/target")
    "load plugins from .jar in a directory" in {
      val (_, pluginsDef) = PluginsLoader(config).load.accepted
      val (_, locator)    = WiringInitializer(serviceModule, pluginsDef).accepted
      val route           = locator.get[PriorityRoute]

      pluginsDef.head.priority shouldEqual 10
      Get("/test-plugin") ~> route.route ~> check {
        responseAs[String] shouldEqual "test,test2"
      }
    }

    "load overriding priority" in {
      System.setProperty("plugins.testplugin.priority", "20")
      ConfigImpl.reloadSystemPropertiesConfig()
      val pluginDef = PluginsLoader(config).load.map(_._2).accepted.head
      pluginDef.priority shouldEqual 20
      System.clearProperty("testplugin.priority")
    }

  }
}
