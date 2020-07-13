package ch.epfl.bluebrain.nexus.commons.cache

import java.io.File

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import pureconfig.generic.auto._
import pureconfig.ConfigSource

import scala.concurrent.duration._

class KeyValueStoreConfigSpec extends AnyWordSpecLike with Matchers with OptionValues {

  val config = KeyValueStoreConfig(
    10.seconds,
    10.seconds,
    RetryStrategyConfig("exponential", 100.millis, 10.hours, 7, 500.millis)
  )

  "KeyValueStoreConfig" should {

    "read from config file" in {
      val readConfig = ConfigFactory.parseFile(new File(getClass.getResource("/commons/example-store.conf").toURI))
      ConfigSource.fromConfig(readConfig).at("key-value-store").loadOrThrow[KeyValueStoreConfig]
    }
  }
}
