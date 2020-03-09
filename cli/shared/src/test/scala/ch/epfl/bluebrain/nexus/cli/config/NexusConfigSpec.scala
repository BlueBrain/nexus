package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Paths

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig.{ClientConfig, SSEConfig}
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class NexusConfigSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A Nexus Config" should {

    "be created from a passed config file" in {
      val path = Paths.get(getClass().getResource("/nexus-test.conf").toURI())
      NexusConfig(path) shouldEqual Right(config)
    }

    "be created from reference.conf file" in {
      val clientConf = ClientConfig(RetryStrategyConfig("exponential", 100.millis, 20.seconds, 10, "on-server-error"))
      val sseConf    = SSEConfig(None)
      val conf       = NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), None, clientConf, sseConf)
      val path       = Paths.get(s"${genString()}.conf")
      NexusConfig(path) shouldEqual Right(conf)
    }

    "be saved to a file" in {
      val path = Paths.get(s"${genString()}.conf")
      config.write[IO](path).unsafeRunSync() shouldEqual Right(())
      path.toFile.exists() shouldEqual true
      NexusConfig(path) shouldEqual Right(config)
      path.toFile.delete()
    }

  }

}
