package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ResourcesBehaviors}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.UIO
import org.scalatest.Inspectors

class ResourcesImplSpec
    extends AbstractDBSpec
    with ConfigFixtures
    with Inspectors
    with CirceLiteral
    with ResourcesBehaviors {

  override def create: UIO[Resources] =
    for {
      eventLog      <- EventLog.postgresEventLog[Envelope[ResourceEvent]](EventLogUtils.toEnvelope).hideErrors
      (orgs, projs) <- projectSetup
      resources     <- ResourcesImpl(
                         orgs,
                         projs,
                         resourceResolution,
                         (_, _) => UIO.unit,
                         resolverContextResolution,
                         aggregate,
                         eventLog,
                         consistentWrite
                       )
    } yield resources
}
