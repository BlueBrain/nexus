package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, RealmsBehaviors}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.Task

class RealmsImplSpec extends AbstractDBSpec with RealmsBehaviors with ConfigFixtures {

  private def eventLog: Task[EventLog[Envelope[RealmEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Realms] =
    eventLog.flatMap { el =>
      val resolveWellKnown = ioFromMap(
        Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
        (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
      )
      RealmsImpl(RealmsConfig(aggregate, keyValueStore, pagination, indexing), resolveWellKnown, el)
    }
}
