package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Paths

import cats.effect.IO
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig._
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.ReadConvertError
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors}

import scala.concurrent.duration._

class NexusConfigSpec extends AnyWordSpecLike with Matchers with Fixtures with EitherValues with Inspectors {

  "A Nexus Config" should {

    "be created from a passed config file" in {
      val path = Paths.get(getClass().getResource("/nexus-test.conf").toURI())
      NexusConfig(path) shouldEqual Right(config)
    }

    "be created from passed config file and overriding parameters" in {
      val token    = BearerToken("myothertoken")
      val endpoint = Uri.unsafeFromString("https://other.nexus.ch")
      val path     = Paths.get(getClass().getResource("/nexus-test.conf").toURI())
      NexusConfig.withDefaults(Some(path), endpoint = Some(endpoint), token = Some(token)) shouldEqual
        Right(config.copy(endpoint = endpoint, token = Some(token)))
    }

    "be created from reference.conf file" in {
      val clientConf = ClientConfig(RetryStrategyConfig("exponential", 100.millis, 20.seconds, 10, "on-server-error"))
      val conf       = NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), None, clientConf)
      val path       = Paths.get(s"${genString()}.conf")
      NexusConfig(path) shouldEqual Right(conf)
    }

    "be saved to a file" in {
      val paths = List(Paths.get(s"${genString()}.conf") -> false, defaultPath.toOption.value -> true)
      forAll(paths) {
        case (path, default) =>
          val write = if (default) config.write[IO]() else config.write[IO](path)
          write.unsafeRunSync() shouldEqual Right(())
          path.toFile.exists() shouldEqual true
          NexusConfig(path) shouldEqual Right(config)
          path.toFile.delete()
      }
    }

    "fail" in {
      val path = Paths.get(getClass().getResource("/nexus-fail-test.conf").toURI())
      val err  = NexusConfig(path).left.value
      err shouldBe a[ReadConvertError]
      err.show.contains("the application configuration failed to be loaded into a configuration object") shouldEqual true
    }

  }

}
