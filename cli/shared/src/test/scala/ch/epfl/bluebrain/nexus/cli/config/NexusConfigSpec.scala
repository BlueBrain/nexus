package ch.epfl.bluebrain.nexus.cli.config

import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig._

class NexusConfigSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A Nexus Config" should {

    "be converted successfully" in {
      val c = ConfigFactory.load("nexus-test.conf")
      ConfigSource.fromConfig(c).at("app").loadOrThrow[NexusConfig] shouldEqual config
    }
  }

}
