package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.sourcing.config.DatabaseFlavour
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.impl.ConfigImpl
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AppConfigSpec extends AnyWordSpecLike with Matchers with IOValues with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    System.clearProperty("app.database.flavour")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  override protected def afterAll(): Unit = {
    System.clearProperty("app.database.flavour")
    super.afterAll()
  }

  "AppConfig" should {

    "load cassandra configuration by default" in {
      val (conf, _) = AppConfig.load().accepted

      conf.database.flavour shouldEqual DatabaseFlavour.Cassandra
    }

    "load postgresql configuration when defined" in {
      System.setProperty("app.database.flavour", "postgres")
      ConfigImpl.reloadSystemPropertiesConfig()
      val (conf, _) = AppConfig.load().accepted

      conf.database.flavour shouldEqual DatabaseFlavour.Postgres
    }

  }

}
