package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.server.Route
import cats.effect.{ContextShift, IO, Resource, Timer}
import ch.epfl.bluebrain.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie._
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.{Module, ModuleDef}
import izumi.distage.model.plan.Roots
import izumi.distage.planning.solver.PlanVerifier
import munit.AnyFixture

import java.nio.file.{Files, Paths}

/**
  * Test class that allows to check that across core and plugins:
  *   - Plugins have been successfully assembled
  *   - HOCON configuration files match their classes counterpart
  *   - Distage wiring is valid
  */
class MainSuite extends CatsEffectSuite with MainSuite.Fixture {

  private val pluginsParentPath  = Paths.get("target/plugins").toAbsolutePath
  private val pluginLoaderConfig = PluginLoaderConfig(pluginsParentPath.toString)

  override def munitFixtures: Seq[AnyFixture[_]] = List(main)

  test("ensure the plugin jar files have been copied correctly") {
    if (Files.list(pluginsParentPath).toArray.length == 0)
      fail(s"No plugin jar files were found in '$pluginsParentPath'")
  }

  test("yield a correct plan") {
    val catsEffectModule         = new ModuleDef {
      make[ContextShift[IO]].fromValue(contextShift)
      make[Timer[IO]].fromValue(timer)
    }
    val (cfg, config, cl, pDefs) = Main.loadPluginsAndConfig(pluginLoaderConfig).accepted
    val pluginsInfoModule        = new ModuleDef { make[List[PluginDef]].from(pDefs) }
    val modules: Module          =
      (catsEffectModule :: DeltaModule(cfg, config, cl) :: pluginsInfoModule :: pDefs.map(_.module)).merge

    PlanVerifier()
      .verify[IO](
        bindings = modules,
        roots = Roots.Everything,
        providedKeys = Set.empty,
        excludedActivations = Set.empty
      )
      .throwOnError()
  }

  test("load different configurations and create the object graph") {
    ConfigImpl.reloadSystemPropertiesConfig()
    Main
      .start(pluginLoaderConfig)
      .use { locator =>
        IO.delay(locator.get[Vector[Route]])
      }
      .void
  }
}

object MainSuite {

  trait Fixture { self: CatsEffectSuite =>

    // Overload config via system properties
    private def acquire(postgres: PostgresContainer, elastic: ElasticSearchContainer): IO[Unit] = IO.delay {
      val resourceTypesFile = Files.createTempFile("resource-types", ".json")
      Files.writeString(resourceTypesFile, """["https://neuroshapes.org/Entity"]""")
      val mappingFile       = Files.createTempFile("mapping", ".json")
      Files.writeString(mappingFile, "{}")
      val queryFile         = Files.createTempFile("query", ".json")
      Files.writeString(
        queryFile,
        """CONSTRUCT { {resource_id} <http://schema.org/name> ?name } WHERE { {resource_id} <http://localhost/name> ?name }"""
      )
      System.setProperty("app.defaults.database.access.host", postgres.getHost)
      System.setProperty("app.defaults.database.access.port", postgres.getMappedPort(5432).toString)
      System.setProperty("app.database.tables-autocreate", "true")
      System.setProperty("app.defaults.database.access.username", PostgresUser)
      System.setProperty("app.default.database.access.password", PostgresPassword)
      System.setProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout", "false")
      System.setProperty("plugins.elasticsearch.base", s"http://${elastic.getHost}:${elastic.getMappedPort(9200)}")
      System.setProperty("plugins.elasticsearch.credentials.username", "elastic")
      System.setProperty("plugins.elasticsearch.credentials.password", "password")
      //TODO Investigate how to remove this property from the config
      System.setProperty("plugins.elasticsearch.disable-metrics-projection", "true")

      System.setProperty("plugins.graph-analytics.enabled", "true")
      System.setProperty("plugins.search.enabled", "true")
      System.setProperty("plugins.search.indexing.resource-types", resourceTypesFile.toString)
      System.setProperty("plugins.search.indexing.mapping", mappingFile.toString)
      System.setProperty("plugins.search.indexing.query", queryFile.toString)

      ConfigImpl.reloadSystemPropertiesConfig()
    }

    // Resetting system properties
    private def release = IO.delay {
      System.clearProperty("app.defaults.database.access.host")
      System.clearProperty("app.defaults.database.access.port")
      System.clearProperty("app.defaults.database.access.username")
      System.clearProperty("app.defaults.database.access.password")
      System.clearProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout")
      System.clearProperty("plugins.elasticsearch.credentials.username")
      System.clearProperty("plugins.elasticsearch.credentials.password")

      System.clearProperty("plugins.graph-analytics.enabled")
      System.clearProperty("plugins.search.enabled")

      System.clearProperty("plugins.search.indexing.mapping")
      System.clearProperty("plugins.search.indexing.query")
      System.clearProperty("plugins.search.indexing.resource-types")
      ConfigImpl.reloadSystemPropertiesConfig()
    }

    // Start the necessary containers
    private def resource() =
      for {
        postgres <- PostgresContainer.resource(PostgresUser, PostgresPassword)
        elastic  <- ElasticSearchContainer.resource()
        _        <- Resource.make(acquire(postgres, elastic))(_ => release)
      } yield ()

    val main: IOFixture[Unit] = ResourceFixture.suiteLocal("main", resource())

  }

}
