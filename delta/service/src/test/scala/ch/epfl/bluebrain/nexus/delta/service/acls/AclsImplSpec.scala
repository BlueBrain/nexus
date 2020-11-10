package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.minimum
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsBehaviors, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Permissions}
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.Task
import org.scalatest.{Inspectors, OptionValues}

class AclsImplSpec
    extends AbstractDBSpec
    with AclsBehaviors
    with OptionValues
    with Inspectors
    with CirceLiteral
    with ConfigFixtures {

  private def eventLog: Task[EventLog[Envelope[AclEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[(Acls, Permissions)] =
    for {
      el <- eventLog
      p  <- PermissionsDummy(minimum)
      a  <- AclsImpl(AclsConfig(aggregate, keyValueStore, indexing), p, el)
    } yield (a, p)

}
