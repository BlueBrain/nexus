package ch.epfl.bluebrain.nexus.delta.service.organizations

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.OrganizationsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, IndexingConfig}
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.{EventLog, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.Task
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.duration._

class OrganizationsImplSpec
    extends AbstractDBSpec
    with OrganizationsBehaviors
    with OptionValues
    with Inspectors
    with CirceLiteral {

  private def eventLog: Task[EventLog[Envelope[OrganizationEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Organizations] =
    eventLog.flatMap { el =>
      OrganizationsImpl(
        OrganizationsConfig(
          aggregate = AggregateConfig(
            askTimeout = Timeout(5.seconds),
            evaluationMaxDuration = 1.second,
            evaluationExecutionContext = system.executionContext,
            stashSize = 100
          ),
          keyValueStore = KeyValueStoreConfig(
            askTimeout = 5.seconds,
            consistencyTimeout = 2.seconds,
            RetryStrategyConfig.AlwaysGiveUp
          ),
          indexing = IndexingConfig(
            1,
            RetryStrategyConfig.ConstantStrategyConfig(1.second, 10)
          ),
          pagination = PaginationConfig(
            defaultSize = 30,
            sizeLimit = 100,
            fromLimit = 10000
          )
        ),
        el
      )
    }
}
