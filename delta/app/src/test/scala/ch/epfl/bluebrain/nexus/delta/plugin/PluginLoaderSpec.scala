package ch.epfl.bluebrain.nexus.delta.plugin

import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.IOValues
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext

class PluginLoaderSpec extends AnyWordSpecLike with ScalatestRouteTest with Matchers with IOValues {

  private val baseUri       = BaseUri.withoutPrefix("http://localhost")
  private val serviceModule = new ModuleDef {
    make[BaseUri].fromValue(baseUri)
    make[Scheduler].from(Scheduler.global)
  }

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

  "A PluginLoader" should {
    val config = PluginLoaderConfig("../plugins/test-plugin/target")
    "load plugins from .jar in a directory" in {
      val (_, pluginsDef) = PluginsLoader(config).load.accepted
      WiringInitializer(serviceModule, pluginsDef)
        .mapK(ioToTaskK)
        .use { case (_, locator) =>
          Task.delay {
            val route = locator.get[Set[PriorityRoute]].head
            pluginsDef.head.priority shouldEqual 10
            Get("/test-plugin") ~> route.route ~> check {
              responseAs[String] shouldEqual "http://localhost"
            }
          }
        }
        .accepted
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
