package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.sdk.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.SchemasBehaviors
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.UIO
import org.scalatest.Inspectors

class SchemasImplSpec
    extends AbstractDBSpec
    with ConfigFixtures
    with Inspectors
    with CirceLiteral
    with SchemasBehaviors {

  override def create: UIO[Schemas] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[SchemaEvent]](EventLogUtils.toEnvelope).hideErrors
      (_, projs) <- projectSetup
      resources  <- SchemasImpl(projs, aggregate, eventLog)
    } yield resources
}
