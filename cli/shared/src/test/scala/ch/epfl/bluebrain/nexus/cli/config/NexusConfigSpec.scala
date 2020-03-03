package ch.epfl.bluebrain.nexus.cli.config

import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import com.typesafe.config.ConfigFactory
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig._

class NexusConfigSpec extends AnyWordSpecLike with Matchers {

  "A Nexus Config" should {

    "be converted successfully" in {
      val config = ConfigFactory.load("nexus-test.conf")
      ConfigSource.fromConfig(config).at("app").loadOrThrow[NexusConfig] shouldEqual
        NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), Some(BearerToken("mytoken")))
    }
  }

}
