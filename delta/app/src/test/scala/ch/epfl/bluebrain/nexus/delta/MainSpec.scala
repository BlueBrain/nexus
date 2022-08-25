package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker._
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOValues}
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.Locator
import izumi.distage.model.definition.{Module, ModuleDef}
import izumi.distage.model.plan.Roots
import izumi.distage.planning.solver.PlanVerifier
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.duration._
import scala.reflect.io.Directory

class MainSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with OptionValues
    with PostgresDocker
    with ElasticSearchDocker {

  private val folder = s"/tmp/delta-cache/${UUID.randomUUID()}"

  override def beforeAll(): Unit = {
    super.beforeAll()
    Files.createDirectories(Path.of(folder))
    val resourceTypesFile = Files.createTempFile("resource-types", ".json")
    Files.writeString(resourceTypesFile, """["https://neuroshapes.org/Entity"]""")
    val mappingFile       = Files.createTempFile("mapping", ".json")
    Files.writeString(mappingFile, "{}")
    val queryFile         = Files.createTempFile("query", ".json")
    Files.writeString(
      queryFile,
      """CONSTRUCT { {resource_id} <http://schema.org/name> ?name } WHERE { {resource_id} <http://localhost/name> ?name }"""
    )
    System.setProperty("app.defaults.database.access.host", hostConfig.host)
    System.setProperty("app.database.database.access.port", hostConfig.port.toString)
    System.setProperty("app.database.database.access.username", PostgresUser)
    System.setProperty("app.database.database.access.password", PostgresPassword)
    System.setProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout", "false")
    System.setProperty("plugins.elasticsearch.credentials.username", "elastic")
    System.setProperty("plugins.elasticsearch.credentials.password", "password")

    //TODO Remove the lines where plugins are migrated
    System.setProperty("plugins.archive.enabled", "false")

    //TODO Enable these plugins again after migration
    System.setProperty("plugins.graph-analytics.enabled", "false")
    System.setProperty("plugins.search.enabled", "true")
    System.setProperty("plugins.search.indexing.resource-types", resourceTypesFile.toString)
    System.setProperty("plugins.search.indexing.mapping", mappingFile.toString)
    System.setProperty("plugins.search.indexing.query", queryFile.toString)

    ConfigImpl.reloadSystemPropertiesConfig()
  }

  override def afterAll(): Unit = {
    System.clearProperty("app.defaults.database.access.host")
    System.clearProperty("app.defaults.database.access.port")
    System.clearProperty("app.defaults.database.access.username")
    System.clearProperty("app.defaults.database.access.password")
    System.clearProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout")
    System.clearProperty("plugins.elasticsearch.credentials.username")
    System.clearProperty("plugins.elasticsearch.credentials.password")

    System.clearProperty("plugins.archive.enabled")

    System.clearProperty("plugins.graph-analytics.enabled.enabled")
    System.clearProperty("plugins.search.enabled")

    System.clearProperty("plugins.search.indexing.mapping")
    System.clearProperty("plugins.search.indexing.query")
    System.clearProperty("plugins.search.indexing.resource-types")

    new Directory(new File(folder)).deleteRecursively()
    super.afterAll()
  }

  "Main" should {
    implicit val sc: Scheduler = Scheduler.global
    val pluginsParentPath      = Paths.get("target/plugins").toAbsolutePath
    val pluginLoaderConfig     = PluginLoaderConfig(pluginsParentPath.toString)

    "ensure the plugin jar files have been copied correctly" in {
      if (Files.list(pluginsParentPath).toArray.length > 0) succeed
      else fail(s"No plugin jar files were found in '$pluginsParentPath'")
    }

    "yield a correct plan" in {
      val (cfg, config, cl, pDefs) = Main.loadPluginsAndConfig(pluginLoaderConfig).accepted
      val pluginsInfoModule        = new ModuleDef { make[List[PluginDef]].from(pDefs) }
      val modules: Module          = (DeltaModule(cfg, config, cl) :: pluginsInfoModule :: pDefs.map(_.module)).merge

      PlanVerifier()
        .verify[Task](
          bindings = modules,
          roots = Roots.Everything,
          providedKeys = Set.empty,
          excludedActivations = Set.empty
        )
        .throwOnError()
    }

    "load different configurations and create the object graph" in {
      ConfigImpl.reloadSystemPropertiesConfig()
      val ref: IORef[Option[Locator]] = IORef.unsafe(None)
      try {
        Main.start(locator => ref.set(Some(locator)), pluginLoaderConfig).acceptedWithTimeout(1.minute)
        val locator = ref.get.accepted.value
        // test wiring correctness
        val _       = locator.get[Vector[Route]]
      } finally {
        val system = ref.get.accepted.value.get[ActorSystem[Nothing]]
        system.terminate()
        Task.fromFuture(system.whenTerminated).as(()).acceptedWithTimeout(20.seconds)
      }
    }
  }

}
