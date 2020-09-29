package ch.epfl.bluebrain.nexus.delta.service.permissions

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsBehaviours
import ch.epfl.bluebrain.nexus.delta.service.AbstractDBSpec
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

class PermissionsImplSpec extends AbstractDBSpec("permissions-test.conf") with PermissionsBehaviours {

  private def eventLog: Task[EventLog[PermissionsEvent]] =
    EventLog.jdbcEventLog(
      { ee =>
        ee.event match {
          case pe: PermissionsEvent => UIO.pure(Some(pe))
          case _                    => UIO.pure(None)
        }
      }
    )

  override def create: Task[Permissions] = {
    eventLog.flatMap { el =>
      PermissionsImpl(
        PermissionsBehaviours.minimum,
        "http://localhost:8080/v1",
        AggregateConfig(
          askTimeout = Timeout(5.seconds),
          evaluationMaxDuration = 1.second,
          evaluationExecutionContext = system.executionContext,
          stashSize = 100
        ),
        el
      )
    }
  }

  override def resourceId: Iri = iri"http://localhost:8080/v1/permissions"
}
