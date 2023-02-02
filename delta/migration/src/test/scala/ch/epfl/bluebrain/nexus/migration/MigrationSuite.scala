package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclFixtures, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.bio.Task

import java.time.Instant
import java.util.UUID

class MigrationSuite extends BioSuite with TestHelpers with AclFixtures with IOValues {

  private val projectsToIgnore = Set("dummy", "myorg/test")
  private val uuid             = UUID.randomUUID()

  test("An ACL event should not be ignored") {
    val payload = jsonContentOf("events/acl-appended.json")
    val event   = ToMigrateEvent(Acls.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(!Migration.toIgnore(event, projectsToIgnore))
  }

  test("An ACL event whose address contains a blacklisted project should be ignored") {
    val payload = jsonContentOf("events/acl-appended-blacklist.json")
    val event   = ToMigrateEvent(Acls.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(Migration.toIgnore(event, projectsToIgnore))
  }

  test("A scoped event that is not in a blacklisted project should not be ignored") {
    val payload = jsonContentOf("events/resolver-created.json")
    val event   = ToMigrateEvent(Resolvers.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(!Migration.toIgnore(event, projectsToIgnore))
  }

  test("A scoped event that is in a blacklisted project should be ignored") {
    val payload = jsonContentOf("events/resolver-created-blacklist.json")
    val event   = ToMigrateEvent(Resolvers.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(Migration.toIgnore(event, projectsToIgnore))
  }

  test("A project event that is not in a blacklisted project should not be ignored") {
    val payload = jsonContentOf("events/project-created.json")
    val event   = ToMigrateEvent(Projects.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(!Migration.toIgnore(event, projectsToIgnore))
  }

  test("A ProjectEvent that is in a blacklisted project should be ignored") {
    val payload = jsonContentOf("events/project-created-blacklist.json")
    val event   = ToMigrateEvent(Projects.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(Migration.toIgnore(event, projectsToIgnore))
  }

  private val projectPayload = jsonContentOf("events/project-created.json")
  private val projectEvent   = ToMigrateEvent(Projects.entityType, "id", 1L, projectPayload, Instant.EPOCH, uuid)

  test("An invalid state should not be rejected") {
    val ml = new MigrationLog {
      override def entityType: EntityType = Projects.entityType

      override def apply(event: ToMigrateEvent): Task[Unit] =
        Task.raiseError(InvalidState(None, event))
    }
    Migration.processEvent(Set(ml))(projectEvent).accepted
  }

}
