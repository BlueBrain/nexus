package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, IndexingConfig}
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.{EventLog, RetryStrategyConfig}
import monix.bio.Task
import org.scalatest.OptionValues

import scala.concurrent.duration._

class RealmsImplSpec extends AbstractDBSpec with RealmsBehaviors with OptionValues {

  private def eventLog: Task[EventLog[Envelope[RealmEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Realms] =
    eventLog.flatMap { el =>
      RealmsImpl(
        RealmsConfig(
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
          pagination = PaginationConfig(
            defaultSize = 30,
            sizeLimit = 100,
            fromLimit = 10000
          ),
          IndexingConfig(
            1,
            RetryStrategyConfig.ConstantStrategyConfig(1.second, 10)
          )
        ),
        ioFromMap(
          Map(
            githubOpenId -> githubWk,
            gitlabOpenId -> gitlabWk
          ),
          (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
        ),
        el
      )
    }
}
