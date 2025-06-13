package ai.senscience.nexus.delta

import ai.senscience.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ai.senscience.nexus.delta.wiring.DeltaModule
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.{PostgresDb, PostgresPassword, PostgresUser}
import ch.epfl.bluebrain.nexus.testkit.config.SystemPropertyOverride
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.definition.{Module, ModuleDef}
import izumi.distage.model.plan.Roots
import izumi.distage.planning.solver.PlanVerifier
import munit.catseffect.IOFixture
import munit.{AnyFixture, CatsEffectSuite}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.Duration

/**
  * Test class that allows to check that across core and plugins:
  *   - Plugins have been successfully assembled
  *   - HOCON configuration files match their classes counterpart
  *   - Distage wiring is valid
  */
class MainSuite extends NexusSuite with MainSuite.Fixture {

  override val munitIOTimeout: Duration = Duration(60, "s")

  private val pluginsParentPath  = Paths.get("target/plugins").toAbsolutePath
  private val pluginLoaderConfig = PluginLoaderConfig(pluginsParentPath.toString)

  override def munitFixtures: Seq[AnyFixture[?]] = List(main)

  test("ensure the plugin jar files have been copied correctly") {
    if (Files.list(pluginsParentPath).toArray.length == 0)
      fail(s"No plugin jar files were found in '$pluginsParentPath'")
  }

  test("yield a correct plan") {
    val (cfg, config, cl, pDefs) = Main.loadPluginsAndConfig(pluginLoaderConfig).accepted
    val pluginsInfoModule        = new ModuleDef { make[List[PluginDef]].from(pDefs) }
    val modules: Module          =
      (DeltaModule(cfg, config, cl) :: pluginsInfoModule :: pDefs.map(_.module)).merge

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
    private def initConfig(postgres: PostgresContainer): IO[Map[String, String]] =
      IO.blocking {
        val resourceTypesFile = Files.createTempFile("resource-types", ".json")
        Files.writeString(resourceTypesFile, """["https://neuroshapes.org/Entity"]""")
        val mappingFile       = Files.createTempFile("mapping", ".json")
        Files.writeString(mappingFile, "{}")
        val queryFile         = Files.createTempFile("query", ".json")
        Files.writeString(
          queryFile,
          """CONSTRUCT { {resource_id} <http://schema.org/name> ?name } WHERE { {resource_id} <http://localhost/name> ?name }"""
        )

        Map(
          "app.defaults.database.access.host"                -> postgres.getHost,
          "app.defaults.database.access.port"                -> postgres.getMappedPort(5432).toString,
          "app.database.tables-autocreate"                   -> "true",
          "app.defaults.database.access.username"            -> PostgresUser,
          "app.default.database.access.password"             -> PostgresPassword,
          "plugins.elasticsearch.indexing-enabled"           -> "false",
          // TODO Investigate how to remove this property from the config
          "plugins.elasticsearch.disable-metrics-projection" -> "true",
          "plugins.graph-analytics.enabled"                  -> "true",
          "plugins.graph-analytics.indexing-enabled"         -> "false",
          "plugins.search.enabled"                           -> "true",
          "plugins.search.indexing.resource-types"           -> resourceTypesFile.toString,
          "plugins.search.indexing.mapping"                  -> mappingFile.toString,
          "plugins.search.indexing.query"                    -> queryFile.toString,
          "otel.sdk.disabled"                                -> "true"
        )
      }

    // Start the necessary containers
    private def resource() =
      for {
        postgres <- PostgresContainer.resource(PostgresUser, PostgresPassword, PostgresDb)
        _        <- SystemPropertyOverride(initConfig(postgres))
      } yield ()

    val main: IOFixture[Unit] = ResourceSuiteLocalFixture("main", resource())

  }

}
