package ch.epfl.bluebrain.nexus.delta.service.plugin

import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PluginLoaderSpec extends AnyWordSpecLike with ScalatestRouteTest with Matchers with IOValues {

  private val perms         = PermissionsDummy(Set(Permission.unsafe("test"), Permission.unsafe("test2")))
  private val serviceModule = new ModuleDef { make[Permissions].fromEffect(perms) }

  "A PluginLoader" should {
    val config = PluginLoaderConfig("../plugins/test-plugin/target")
    "load plugins from .jar in a directory" in {
      val pluginsDef   = PluginsLoader(config).load.map(_._2).accepted
      val (plugins, _) = PluginsInitializer(serviceModule, pluginsDef).accepted
      val route        = plugins.flatMap(_.route).head

      pluginsDef.head.priority shouldEqual 10
      Get("/test-plugin") ~> route ~> check {
        responseAs[String] shouldEqual "test,test2"
      }
    }

    "load overriding priority" in {
      System.setProperty("testplugin.priority", "20")
      ConfigImpl.reloadSystemPropertiesConfig()
      val pluginDef = PluginsLoader(config).load.map(_._2).accepted.head
      pluginDef.priority shouldEqual 20
      System.clearProperty("testplugin.priority")
    }

  }
}
