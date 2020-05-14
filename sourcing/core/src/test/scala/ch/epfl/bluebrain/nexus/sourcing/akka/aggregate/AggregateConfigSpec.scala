package ch.epfl.bluebrain.nexus.sourcing.akka.aggregate

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig.{AkkaAggregateConfig, PassivationStrategyConfig}
import ch.epfl.bluebrain.nexus.sourcing.{RetryStrategyConfig, SourcingSpec}
import com.typesafe.config.ConfigFactory
import pureconfig.generic.auto._
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AggregateConfigSpec extends TestKit(ActorSystem("SourcingConfigSpec")) with SourcingSpec {

  val config = aggregate.AggregateConfig(
    10.seconds,
    "inmemory-journal",
    5.seconds,
    "global",
    10,
    PassivationStrategyConfig(Some(5.seconds), Some(0.milliseconds)),
    RetryStrategyConfig("exponential", 100.milliseconds, 10.hours, 7, 500.milliseconds)
  )

  "SourcingConfig" should {

    "read from config file" in {
      val readConfig = ConfigFactory.parseFile(new File(getClass.getResource("/example-aggregate.conf").toURI))
      ConfigSource.fromConfig(readConfig).at("aggregate").loadOrThrow[AggregateConfig] shouldEqual config
    }

    "return AkkaSourcingConfig" in {
      config.akkaAggregateConfig shouldEqual AkkaAggregateConfig(
        10.second,
        "inmemory-journal",
        5.seconds,
        ExecutionContext.global
      )
    }
    "return passivation strategy" in {
      val strategy = config.passivationStrategy()

      strategy.lapsedSinceLastInteraction.value shouldEqual 5.seconds
      strategy.lapsedSinceRecoveryCompleted.value shouldEqual 0.milliseconds
    }

  }
}
