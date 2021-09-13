package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{SchemaEvent, SchemasConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, SchemasBehaviors}
import ch.epfl.bluebrain.nexus.delta.sdk.{ResourceIdCheck, Schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
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
      cache         <- SchemasImpl.cache(SchemasConfig(aggregate, 10))
      agg           <- SchemasImpl.aggregate(aggregate, ResourceIdCheck.alwaysAvailable)
      schemas        = SchemasImpl(orgs, projs, schemaImports, resolverContextResolution, eventLog, agg, cache)
    } yield schemas
}
