package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.minimum
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent.{PermissionsAppended, PermissionsDeleted, PermissionsReplaced, PermissionsSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection.{CannotAppendEmptyCollection, CannotDeleteMinimumCollection, CannotReplaceWithEmptyCollection, CannotSubtractEmptyCollection, CannotSubtractFromMinimumCollection, CannotSubtractUndefinedPermissions, IncorrectRev, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.{DoobieFixture, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.CancelAfterFailure
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

class PermissionsImplSpec extends DoobieFixture with Matchers with IOValues with CancelAfterFailure with IOFixedClock {

  implicit def subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit def scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  private val eventLogConfig = EventLogConfig(QueryConfig(5, RefreshStrategy.Delay(100.millis)), 100.millis)

  private val config = PermissionsConfig(
    eventLogConfig,
    PermissionsGen.minimum,
    Set.empty
  )

  private lazy val permissions: Permissions = PermissionsImpl(config, xas)

  "A permissions permissions implementation" should {
    val read: Permission = Permissions.permissions.read

    val perm1: Permission = Permission.unsafe(genString())
    val perm2: Permission = Permission.unsafe(genString())
    val perm3: Permission = Permission.unsafe(genString())
    val perm4: Permission = Permission.unsafe(genString())

    "echo the minimum permissions" in {
      permissions.minimum shouldEqual minimum
    }
    "return the minimum permissions set" in {
      permissions.fetchPermissionSet.accepted shouldEqual minimum
    }
    "return the minimum permissions resource" in {
      permissions.fetch.accepted shouldEqual PermissionsGen.resourceFor(minimum, rev = 0)
    }
    "fail to delete minimum when initial" in {
      permissions.delete(0).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "fail to subtract with incorrect rev" in {
      permissions.subtract(Set(perm1), 1).rejected shouldEqual IncorrectRev(1, 0)
    }
    "fail to subtract from minimum" in {
      permissions.subtract(Set(perm1), 0).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "fail to subtract undefined permissions" in {
      permissions.append(Set(perm1), 0).accepted
      permissions.fetchPermissionSet.accepted shouldEqual (minimum + perm1)
      permissions.subtract(Set(perm2), 1).rejected shouldEqual CannotSubtractUndefinedPermissions(Set(perm2))
    }
    "fail to subtract empty permissions" in {
      permissions.subtract(Set.empty, 1).rejected shouldEqual CannotSubtractEmptyCollection
    }
    "fail to subtract from minimum collection" in {
      permissions.subtract(Set(read), 1).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "subtract a permission" in {
      permissions.subtract(Set(perm1), 1).accepted
      permissions.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to append with incorrect rev" in {
      permissions.append(Set(perm1), 0).rejected shouldEqual IncorrectRev(0, 2)
    }
    "append permissions" in {
      permissions.append(Set(perm1, perm2), 2).accepted
      permissions.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm1, perm2))
    }
    "fail to append duplicate permissions" in {
      permissions.append(Set(perm2), 3).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to append empty permissions" in {
      permissions.append(Set.empty, 3).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to replace with incorrect rev" in {
      permissions.replace(Set(perm3), 1).rejected shouldEqual IncorrectRev(1, 3)
    }
    "fail to replace with empty permissions" in {
      permissions.replace(Set.empty, 3).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "fail to replace with subset of minimum" in {
      permissions.replace(Set(read), 3).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "replace non minimum" in {
      permissions.replace(Set(perm3, perm4), 3).accepted
      permissions.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm3, perm4))
    }
    "fail to delete with incorrect rev" in {
      permissions.delete(2).rejected shouldEqual IncorrectRev(2, 4)
    }
    "delete permissions" in {
      permissions.delete(4).accepted
      permissions.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to delete minimum permissions" in {
      permissions.delete(5).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "return minimum for revision 0" in {
      permissions.fetchAt(0).accepted.value.permissions shouldEqual minimum
    }
    "return revision for correct rev" in {
      permissions.fetchAt(4).accepted.value shouldEqual model.PermissionSet(minimum ++ Set(perm3, perm4))
    }
    "return none for unknown rev" in {
      permissions.fetchAt(9999).rejected shouldEqual RevisionNotFound(9999, 5)
    }
    "return all the current events" in {
      permissions.currentEvents().compile.toVector.accepted.map(_.value) shouldEqual Vector(
        PermissionsAppended(1, Set(perm1), Instant.EPOCH, subject),
        PermissionsSubtracted(2, Set(perm1), Instant.EPOCH, subject),
        PermissionsAppended(3, Set(perm1, perm2), Instant.EPOCH, subject),
        PermissionsReplaced(4, Set(perm3, perm4), Instant.EPOCH, subject),
        PermissionsDeleted(5, Instant.EPOCH, subject)
      )
    }

    "return some of the current events" in {
      // format: off
      val envelopes = permissions.currentEvents(Offset.at(2)).compile.toVector.accepted
      envelopes shouldEqual Vector(
        Envelope(entityType, entityId, 3, PermissionsAppended(3, Set(perm1, perm2), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(3L)),
        Envelope(entityType, entityId, 4, PermissionsReplaced(4, Set(perm3, perm4), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(4L)),
        Envelope(entityType, entityId, 5, PermissionsDeleted(5, Instant.EPOCH, subject), Instant.EPOCH, Offset.at(5L))
      )
      // format: on
    }

    "return a complete non terminating stream of events" in {
      val envelopes = for {
        fiber     <- permissions.events().take(6L).compile.toVector.start
        _         <- permissions.append(Set(perm1, perm2), 5)
        collected <- fiber.join
      } yield collected
      envelopes.accepted.map(_.value) shouldEqual Vector(
        PermissionsAppended(1, Set(perm1), Instant.EPOCH, subject),
        PermissionsSubtracted(2, Set(perm1), Instant.EPOCH, subject),
        PermissionsAppended(3, Set(perm1, perm2), Instant.EPOCH, subject),
        PermissionsReplaced(4, Set(perm3, perm4), Instant.EPOCH, subject),
        PermissionsDeleted(5, Instant.EPOCH, subject),
        PermissionsAppended(6, Set(perm1, perm2), Instant.EPOCH, subject)
      )
    }

    "return a partial non terminating stream of events" in {
      val envelopes = for {
        fiber     <- permissions.events(Offset.at(2L)).take(5L).compile.toVector.start
        _         <- permissions.append(Set(perm3), 6)
        collected <- fiber.join
      } yield collected
        // format: off
        envelopes.accepted shouldEqual Vector(
          Envelope(entityType, entityId, 3, PermissionsAppended(3, Set(perm1, perm2), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(3L)),
          Envelope(entityType, entityId, 4, PermissionsReplaced(4, Set(perm3, perm4), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(4L)),
          Envelope(entityType, entityId, 5, PermissionsDeleted(5, Instant.EPOCH, subject), Instant.EPOCH, Offset.at(5L)),
          Envelope(entityType, entityId, 6, PermissionsAppended(6, Set(perm1, perm2), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(6L)),
          Envelope(entityType, entityId, 7, PermissionsAppended(7, Set(perm3), Instant.EPOCH, subject), Instant.EPOCH, Offset.at(7L))
        )
        // format: on
    }

  }

}
