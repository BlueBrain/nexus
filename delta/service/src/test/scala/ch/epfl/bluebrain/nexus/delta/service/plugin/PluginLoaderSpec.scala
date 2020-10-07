package ch.epfl.bluebrain.nexus.delta.service.plugin

import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginLoader.PluginLoaderConfig
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PluginLoaderSpec extends AnyWordSpecLike with ScalatestRouteTest with Matchers {

  val plConfig = PluginLoaderConfig(Some("../test-plugin/target"))
  val pl       = PluginLoader(plConfig)
  val perms    = PermissionsDummy(Set(Permission.unsafe("test"), Permission.unsafe("test2")))
  val module   = new ModuleDef {
    make[Permissions].fromEffect(perms)
  }

  "A PluginLoader" should {
    "load plugins from .jar in a directory" in {
      val plugins = pl
        .loadAndStartPlugins(module)
        .runSyncUnsafe()

      val route = plugins.flatMap(_.route).head

      Get("/test-plugin") ~> route ~> check {
        responseAs[String] shouldEqual "test,test2"
      }
    }

  }
}
