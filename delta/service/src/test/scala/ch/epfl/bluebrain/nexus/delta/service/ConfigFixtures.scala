package ch.epfl.bluebrain.nexus.delta.service

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

import scala.concurrent.duration._

trait ConfigFixtures {

  implicit def system: ActorSystem[Nothing]

  def aggregate: AggregateConfig =
    AggregateConfig(
      askTimeout = Timeout(5.seconds),
      evaluationMaxDuration = 1.second,
      evaluationExecutionContext = system.executionContext,
      stashSize = 100
    )

  def keyValueStore: KeyValueStoreConfig =
    KeyValueStoreConfig(
      askTimeout = 5.seconds,
      consistencyTimeout = 2.seconds,
      RetryStrategyConfig.AlwaysGiveUp
    )

  def indexing: IndexingConfig = IndexingConfig(1, RetryStrategyConfig.ConstantStrategyConfig(1.second, 10))

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )
}
