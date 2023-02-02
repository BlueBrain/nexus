package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.migration.ToMigrateEvent
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}

import java.time.Instant
import java.util.UUID

class MigrationSuite extends BioSuite with TestHelpers with IOValues {

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
    val event = ToMigrateEvent(Projects.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(!Migration.toIgnore(event, projectsToIgnore))
  }

  test("A ProjectEvent that is in a blacklisted project should be ignored") {
    val payload = jsonContentOf("events/project-created-blacklist.json")
    val event = ToMigrateEvent(Projects.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    assert(Migration.toIgnore(event, projectsToIgnore))
  }

  test("Reject with NoSuchElementException when a MigrationLog is missing") {
    val payload = jsonContentOf("events/project-created-blacklist.json")
    val event = ToMigrateEvent(Projects.entityType, "id", 1L, payload, Instant.EPOCH, uuid)
    Migration.processEvent(Set.empty)(event).rejectedWith[NoSuchElementException]
  }

}
