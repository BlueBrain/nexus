package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Paths

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig.ClientConfig
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
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

    "be created from passed config file and overriding parameters" in {
      val token    = BearerToken("myothertoken")
      val endpoint = Uri.unsafeFromString("https://other.nexus.ch")
      val path     = Paths.get(getClass().getResource("/nexus-test.conf").toURI())
      NexusConfig.withDefaults(path, endpoint = Some(endpoint), token = Some(token)) shouldEqual
        Right(config.copy(endpoint = endpoint, token = Some(token)))
    }

    "be created from reference.conf file" in {
      val clientConf = ClientConfig(RetryStrategyConfig("exponential", 100.millis, 20.seconds, 10, "on-server-error"))
      val conf       = NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), None, clientConf)
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
