package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.sdk.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, SchemasBehaviors}
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
      eventLog      <- EventLog.postgresEventLog[Envelope[SchemaEvent]](EventLogUtils.toEnvelope).hideErrors
      (orgs, projs) <- projectSetup
      resources     <- SchemasImpl(
                         orgs,
                         projs,
                         schemaImports,
                         resolverContextResolution,
                         aggregate,
                         eventLog
                       )
    } yield resources
}
