package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import akka.persistence.query.{EventEnvelope, Sequence}
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.AbstractDBSpec
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.{EventLog, RetryStrategyConfig}
import monix.bio.{Task, UIO}
import org.scalatest.OptionValues

import scala.concurrent.duration._

class RealmsImplSpec extends AbstractDBSpec with RealmsBehaviors with OptionValues {

  private def eventLog: Task[EventLog[Envelope[RealmEvent]]] =
    EventLog.jdbcEventLog {
      case ee @ EventEnvelope(offset: Sequence, persistenceId, sequenceNr, value: RealmEvent) =>
        UIO.pure(Some(Envelope(value, offset, persistenceId, sequenceNr, ee.timestamp)))
      case _                                                                                  => UIO.pure(None)
    }

  implicit lazy val baseUri: BaseUri = BaseUri("http://localhost:8080/v1")

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
