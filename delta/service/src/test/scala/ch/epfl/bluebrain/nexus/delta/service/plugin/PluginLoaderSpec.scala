package ch.epfl.bluebrain.nexus.delta.service.plugin

import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.testkit.IOValues
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PluginLoaderSpec extends AnyWordSpecLike with ScalatestRouteTest with Matchers with IOValues {

  private val perms         = PermissionsDummy(Set(Permission.unsafe("test"), Permission.unsafe("test2")))
  private val serviceModule = new ModuleDef { make[Permissions].fromEffect(perms) }

  "A PluginLoader" should {
    "load plugins from .jar in a directory" in {
      val config       = PluginLoaderConfig("../plugins/test-plugin/target")
      val pluginsDef   = PluginsLoader(config).load.map(_._2).accepted
      val (plugins, _) = PluginsInitializer(serviceModule, pluginsDef).accepted
      val route        = plugins.flatMap(_.route).head

      Get("/test-plugin") ~> route ~> check {
        responseAs[String] shouldEqual "test,test2"
      }
    }

  }
}
