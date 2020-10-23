package ch.epfl.bluebrain.nexus.delta.service.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.Task

class PermissionsImplSpec extends AbstractDBSpec with PermissionsBehaviors with ConfigFixtures {

  private def eventLog: Task[EventLog[Envelope[PermissionsEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Permissions] = {
    eventLog.flatMap { el =>
      PermissionsImpl(
        PermissionsBehaviors.minimum,
        BaseUri("http://localhost:8080/v1"),
        aggregate,
        el
      )
    }
  }

  override def resourceId: Iri = iri"http://localhost:8080/v1/permissions"
}
