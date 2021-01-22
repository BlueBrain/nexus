package ch.epfl.bluebrain.nexus.delta

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOValues}
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.Locator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, Inspectors, OptionValues}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.reflect.io.Directory

class MainSpec
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with Inspectors
    with IOValues
    with OptionValues {

  private val folder = s"/tmp/delta-cache/${UUID.randomUUID()}"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Files.createDirectories(Path.of(folder))
    System.clearProperty("app.database.flavour")
    System.clearProperty("akka.cluster.distributed-data.durable.lmdb.dir")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  override protected def afterAll(): Unit = {
    System.clearProperty("app.database.flavour")
    System.clearProperty("akka.cluster.distributed-data.durable.lmdb.dir")
    new Directory(new File(folder)).deleteRecursively()
    super.afterAll()
  }

  "Main" should {

    "load different configurations and create the object graph" in {
      val flavours = List("cassandra", "postgres")
      forAll(flavours) { flavour =>
        System.setProperty("app.database.flavour", flavour)
        System.setProperty("akka.cluster.distributed-data.durable.lmdb.dir", folder)
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

}
