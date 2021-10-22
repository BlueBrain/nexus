package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.impl.ConfigImpl
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AppConfigSpec extends AnyWordSpecLike with Matchers with IOValues with BeforeAndAfterAll {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    clearProperties()
  }

  override protected def afterAll(): Unit = {
    clearProperties()
    super.afterAll()
  }

  private def clearProperties(): Unit = {
    System.clearProperty("app.database.flavour")
    System.clearProperty("app.projects.deny-project-pruning")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  "AppConfig" should {

    val externalConfigPath = ClasspathResourceUtils.absolutePath("/config/external.conf").accepted

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

    "load postgresql configuration via an external config file" in {
      clearProperties()
      val (conf, _) = AppConfig
        .load(externalConfigPath = Some(externalConfigPath))
        .accepted

      conf.database.flavour shouldEqual DatabaseFlavour.Postgres
    }

    "load cassandra as system properties have a higher priority than the external config file" in {
      System.setProperty("app.database.flavour", "cassandra")
      ConfigImpl.reloadSystemPropertiesConfig()
      val (conf, _) = AppConfig
        .load(externalConfigPath = Some(externalConfigPath))
        .accepted

      conf.database.flavour shouldEqual DatabaseFlavour.Cassandra
    }

    "fail to load because of cleanup misconfiguration" in {
      System.setProperty("app.projects.deny-project-pruning", "false")
      ConfigImpl.reloadSystemPropertiesConfig()
      AppConfig.load().rejected.head shouldEqual AppConfig.projectPruningMisconfiguration
    }

  }

}
