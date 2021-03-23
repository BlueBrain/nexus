package ch.epfl.bluebrain.nexus.delta

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOValues}
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.Locator
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.reflect.io.Directory

trait MainBehaviors { this: AnyWordSpecLike with Matchers with IOValues with OptionValues =>

  private val folder = s"/tmp/delta-cache/${UUID.randomUUID()}"

  protected def flavour: String

  protected def commonBeforeAll(): Unit = {
    Files.createDirectories(Path.of(folder))
    System.setProperty("app.database.flavour", flavour)
    System.setProperty(s"app.database.${flavour}.keyspace-autocreate", "true")
    System.setProperty(s"app.database.${flavour}.tables-autocreate", "true")
    System.setProperty("akka.cluster.distributed-data.durable.lmdb.dir", folder)
    System.setProperty("akka.remote.artery.canonical.port", "0")
    System.setProperty("akka.cluster.jmx.multi-mbeans-in-same-jvm", "on")
    System.setProperty("datastax-java-driver.basic.request.timeout", "12 seconds")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  protected def commonAfterAll(): Unit = {
    System.clearProperty("app.database.flavour")
    System.clearProperty("akka.cluster.distributed-data.durable.lmdb.dir")
    System.clearProperty("app.database.flavour")
    System.clearProperty("app.database.cassandra.keyspace-autocreate")
    System.clearProperty("app.database.cassandra.tables-autocreate")
    System.clearProperty("akka.cluster.distributed-data.durable.lmdb.dir")
    System.clearProperty("akka.remote.artery.canonical.port")
    System.clearProperty("akka.cluster.jmx.multi-mbeans-in-same-jvm")
    System.clearProperty("datastax-java-driver.basic.request.timeout")
    new Directory(new File(folder)).deleteRecursively()
    ()
  }

  "Main" should {
    implicit val sc: Scheduler = Scheduler.global

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
