package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.impl.ConfigImpl
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AppConfigSpec extends AnyWordSpecLike with Matchers with IOValues {

  "AppConfig" should {

    "load cassandra configuration by default" in {
      val conf = AppConfig.load().accepted

      conf._1.database.flavour shouldEqual DatabaseFlavour.Cassandra
    }

    "load postgresql configuration when defined" in {
      System.setProperty("app.database.flavour", "postgres")
      ConfigImpl.reloadSystemPropertiesConfig()

      val conf = AppConfig.load().accepted

      conf._1.database.flavour shouldEqual DatabaseFlavour.Postgres
      System.clearProperty("app.database.flavour")
    }

  }

}
