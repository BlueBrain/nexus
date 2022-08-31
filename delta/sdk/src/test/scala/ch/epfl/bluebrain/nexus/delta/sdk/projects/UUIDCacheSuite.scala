package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationState
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectState
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{GlobalStateStore, ScopedStateStore}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import munit.AnyFixture

import java.util.UUID
import scala.concurrent.duration._

class UUIDCacheSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private val cacheConfig = CacheConfig(10, 5.minutes)
  private val queryConfig = QueryConfig(5, RefreshStrategy.Stop)

  private val org     = Label.unsafe("org")
  private val orgUuid = UUID.randomUUID()

  private val projectRef = ProjectRef.unsafe("org", "proj")
  private val projUuid   = UUID.randomUUID()

  private lazy val xas = doobie()

  private lazy val uuidCache = UUIDCache(cacheConfig, cacheConfig, xas).runSyncUnsafe()

  private lazy val orgStore     = GlobalStateStore(Organizations.entityType, OrganizationState.serializer, queryConfig, xas)
  private lazy val projectStore = ScopedStateStore(Projects.entityType, ProjectState.serializer, queryConfig, xas)

  test("Save some orgs and projects") {
    (orgStore.save(OrganizationGen.state("org", 1, orgUuid)) >>
      orgStore.save(OrganizationGen.state("anotherOrg", 1, UUID.randomUUID())) >>
      projectStore.save(ProjectGen.state("org", "proj", 1, projUuid)) >>
      projectStore.save(ProjectGen.state("org", "anotherProj", 1, UUID.randomUUID()))).transact(xas.write)
  }

  test("Return the label for an org from an uuid") {
    uuidCache.orgLabel(orgUuid).assertSome(org)
  }

  test("Return None for an unknown uuid for an org") {
    uuidCache.orgLabel(UUID.randomUUID()).assertNone
  }

  test("Return the project ref for an project from an uuid") {
    uuidCache.projectRef(projUuid).assertSome(projectRef)
  }

  test("Return None for an unknown uuid for a project") {
    uuidCache.projectRef(UUID.randomUUID()).assertNone
  }
}
