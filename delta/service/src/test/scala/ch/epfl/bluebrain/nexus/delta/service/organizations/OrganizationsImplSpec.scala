package ch.epfl.bluebrain.nexus.delta.service.organizations

import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.ownerPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, OrganizationsBehaviors}
import ch.epfl.bluebrain.nexus.delta.service.utils.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.Task
import org.scalatest.Inspectors

class OrganizationsImplSpec
    extends AbstractDBSpec
    with OrganizationsBehaviors
    with Inspectors
    with CirceLiteral
    with ConfigFixtures {

  private lazy val config = OrganizationsConfig(aggregate, keyValueStore, pagination, cacheIndexing)

  private def eventLog: Task[EventLog[Envelope[OrganizationEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Organizations] =
    eventLog.flatMap { el =>
      OrganizationsImpl(
        config,
        el,
        Set(new OwnerPermissionsScopeInitialization(acls, ownerPermissions, serviceAccount))
      )
    }
}
