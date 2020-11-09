package ch.epfl.bluebrain.nexus.delta

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import cats.effect.ExitCode
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOValues}
import com.typesafe.config.impl.ConfigImpl
import izumi.distage.model.Locator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class MainSpec extends AnyWordSpecLike with Matchers with Inspectors with IOValues with OptionValues {

  "Main" should {

    "load different configurations and create the object graph" in {

      val flavours = List("cassandra", "postgres")

      forAll(flavours) { flavour =>
        System.setProperty("app.database.flavour", flavour)
        System.setProperty("akka.remote.artery.canonical.port", "0")
        ConfigImpl.reloadSystemPropertiesConfig()

        val ref: IORef[Option[Locator]] = IORef.unsafe(None)

        Main.start(locator => ref.set(Some(locator))).accepted shouldEqual ExitCode.Success

        val locator = ref.get.accepted.value
        locator.get[AppConfig].database.flavour.toString.toLowerCase shouldEqual flavour

        // Testing the actor system and shut it down
        val system  = locator.get[ActorSystem[Nothing]]
        val testkit = ActorTestKit(system)

        val probe = testkit.createTestProbe[String]()
        probe.ref ! "Message"
        probe.expectMessage("Message")
        testkit.shutdownTestKit()

        System.clearProperty("app.database.flavour")
        System.clearProperty("akka.remote.artery.canonical.port")
      }
    }
  }

}
