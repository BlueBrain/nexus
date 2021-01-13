package ch.epfl.bluebrain.nexus.delta.service.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, PermissionsBehaviors}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.Task

class PermissionsImplSpec extends AbstractDBSpec with PermissionsBehaviors with ConfigFixtures {

  private def eventLog: Task[EventLog[Envelope[PermissionsEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Permissions] =
    eventLog.flatMap { el =>
      PermissionsImpl(PermissionsGen.minimum, aggregate, el)
    }

  override def resourceId: Iri = iri"http://localhost:8080/v1/permissions"
}
