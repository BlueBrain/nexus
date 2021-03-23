package ch.epfl.bluebrain.nexus.delta

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.wiring.{DeltaModule, MigrationModule}
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
import scala.reflect.io.Directory

trait MainBehaviors { this: AnyWordSpecLike with Matchers with IOValues with OptionValues =>

  private val folder = s"/tmp/delta-cache/${UUID.randomUUID()}"

  protected def flavour: String

  protected def commonBeforeAll(): Unit = {
    Files.createDirectories(Path.of(folder))
    System.setProperty("app.database.flavour", flavour)
    System.setProperty(s"app.database.$flavour.keyspace-autocreate", "true")
    System.setProperty(s"app.database.$flavour.tables-autocreate", "true")
    System.setProperty("akka.cluster.distributed-data.durable.lmdb.dir", folder)
    System.setProperty("akka.cluster.jmx.multi-mbeans-in-same-jvm", "on")
    System.setProperty("akka.remote.artery.canonical.port", "0")
    System.setProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout", "false")
    System.setProperty("datastax-java-driver.basic.request.timeout", "12 seconds")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  protected def commonAfterAll(): Unit = {
    System.clearProperty("app.database.flavour")
    System.clearProperty(s"app.database.$flavour.tables-autocreate")
    System.clearProperty("app.database.cassandra.keyspace-autocreate")
    System.clearProperty("app.database.cassandra.tables-autocreate")
    System.clearProperty("akka.cluster.distributed-data.durable.lmdb.dir")
    System.clearProperty("akka.cluster.jmx.multi-mbeans-in-same-jvm")
    System.clearProperty("akka.remote.artery.canonical.port")
    System.clearProperty("akka.actor.testkit.typed.throw-on-shutdown-timeout")
    System.clearProperty("datastax-java-driver.basic.request.timeout")
    new Directory(new File(folder)).deleteRecursively()
    ()
  }

  "Main" should {
    implicit val sc: Scheduler = Scheduler.global
    val pluginsParentPath      = Paths.get("delta/app/target/plugins").toAbsolutePath
    val pluginLoaderConfig     = PluginLoaderConfig(pluginsParentPath.toString)

    "yield a correct plan in Normal mode" in {
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

    "yield a correct plan in Migration mode" in {
      val (cfg, config, cl, pDefs) = Main.loadPluginsAndConfig(pluginLoaderConfig).accepted
      val pluginsInfoModule        = new ModuleDef { make[List[PluginDef]].from(pDefs) }
      val modules: Module          = (MigrationModule(cfg, config, cl) :: pluginsInfoModule :: pDefs.map(_.module)).merge

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
      Main.start(locator => ref.set(Some(locator)), PluginLoaderConfig()).accepted

      val locator = ref.get.accepted.value
      // Testing the actor system and shut it down
      val system  = locator.get[ActorSystem[Nothing]]
      val testkit = ActorTestKit(system)

      val probe = testkit.createTestProbe[String]()
      probe.ref ! "Message"
      probe.expectMessage("Message")
      testkit.shutdownTestKit()
    }
  }
}
