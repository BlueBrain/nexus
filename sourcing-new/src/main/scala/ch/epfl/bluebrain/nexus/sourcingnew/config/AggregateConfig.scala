package ch.epfl.bluebrain.nexus.sourcingnew.config

import akka.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final case class AggregateConfig(askTimeout: Timeout,
                                 evaluationMaxDuration: FiniteDuration,
                                 evaluationExecutionContext: ExecutionContext,
                                 stashSize: Int)

