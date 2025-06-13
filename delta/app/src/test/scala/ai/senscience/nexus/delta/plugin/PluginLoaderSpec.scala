package ai.senscience.nexus.delta.plugin

import ai.senscience.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.ModuleDef

class PluginLoaderSpec extends CatsEffectSpec with ScalatestRouteTest {

  private val baseUri       = BaseUri.withoutPrefix(uri"http://localhost")
  private val serviceModule = new ModuleDef {
    make[BaseUri].fromValue(baseUri)
  }

  "A PluginLoader" should {
    val config = PluginLoaderConfig("../plugins/test-plugin/target")
    "load plugins from .jar in a directory" in {
      val (_, pluginsDef) = PluginsLoader(config).load.accepted
      WiringInitializer(serviceModule, pluginsDef).use { case (_, locator) =>
        IO.delay {
          val route = locator.get[Set[PriorityRoute]].head
          pluginsDef.head.priority shouldEqual 10
          Get("/test-plugin") ~> route.route ~> check {
            responseAs[String] shouldEqual "http://localhost"
          }
        }
      }.accepted
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
