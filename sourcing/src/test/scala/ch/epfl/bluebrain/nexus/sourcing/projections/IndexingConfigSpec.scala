package ch.epfl.bluebrain.nexus.sourcing.projections

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig.PersistProgressConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.generic.auto._
import pureconfig.ConfigSource

import scala.concurrent.duration._

class IndexingConfigSpec
    extends TestKit(ActorSystem("IndexingConfigSpec"))
    with AnyWordSpecLike
    with Matchers
    with OptionValues {

  val config = IndexingConfig(
    10,
    40.millis,
    RetryStrategyConfig("exponential", 100.milliseconds, 10.hours, 7, 5.seconds),
    PersistProgressConfig(1000, 5.seconds)
  )

  "IndexingConfig" should {

    "read from config file" in {
      val readConfig = ConfigFactory.parseFile(new File(getClass.getResource("/example-indexing.conf").toURI))
      ConfigSource.fromConfig(readConfig).loadOrThrow[IndexingConfig] shouldEqual config
    }
  }
}
