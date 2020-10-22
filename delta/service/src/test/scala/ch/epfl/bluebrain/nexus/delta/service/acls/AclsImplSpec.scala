package ch.epfl.bluebrain.nexus.delta.service.acls

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsBehaviors.minimum
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsBehaviors, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, IndexingConfig}
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.{EventLog, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.Task
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.duration._

class AclsImplSpec extends AbstractDBSpec with AclsBehaviors with OptionValues with Inspectors with CirceLiteral {

  private def eventLog: Task[EventLog[Envelope[AclEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Acls] =
    eventLog.flatMap { el =>
      AclsImpl(
        AclsConfig(
          AggregateConfig(
            askTimeout = Timeout(5.seconds),
            evaluationMaxDuration = 1.second,
            evaluationExecutionContext = system.executionContext,
            stashSize = 100
          ),
          KeyValueStoreConfig(
            askTimeout = 5.seconds,
            consistencyTimeout = 2.seconds,
            RetryStrategyConfig.AlwaysGiveUp
          ),
          IndexingConfig(
            1,
            RetryStrategyConfig.ConstantStrategyConfig(1.second, 10)
          )
        ),
        PermissionsDummy(minimum),
        el
      )
    }
}
