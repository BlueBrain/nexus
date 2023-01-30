package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
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
    System.clearProperty("app.description.name")
    ConfigImpl.reloadSystemPropertiesConfig()
  }

  "AppConfig" should {

    val externalConfigPath = ClasspathResourceUtils.absolutePath("/config/external.conf").accepted

    "load conf" in {
      val (conf, _) = AppConfig.load().accepted
      conf.description.name.value shouldEqual "delta"
    }

    "load app name via an external config file" in {
      clearProperties()
      val (conf, _) = AppConfig
        .load(externalConfigPath = Some(externalConfigPath))
        .accepted

      conf.description.name.value shouldEqual "override name by file"
    }

    "load app name as system properties have a higher priority than the external config file" in {
      System.setProperty("app.description.name", "override name by property")
      ConfigImpl.reloadSystemPropertiesConfig()
      val (conf, _) = AppConfig
        .load(externalConfigPath = Some(externalConfigPath))
        .accepted

      conf.description.name.value shouldEqual "override name by property"
    }
  }

}
