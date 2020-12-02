package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.SchemasBehaviors
import ch.epfl.bluebrain.nexus.delta.sdk.{SchemaImports, Schemas}
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.{IO, UIO}
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
                         new SchemaImports(
                           (_, _, _) => IO.raiseError(ResourceResolutionReport(Vector.empty)),
                           (_, _, _) => IO.raiseError(ResourceResolutionReport(Vector.empty))
                         ),
                         aggregate,
                         eventLog
                       )
    } yield resources
}
