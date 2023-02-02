package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.migration.MigrationLog.IgnoredInvalidState
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import monix.bio.IO.clock
import munit.AnyFixture

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt

class MigrationLogSuite extends BioSuite with Doobie.Fixture with TestHelpers with IOValues {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)
  private lazy val xas                           = doobie()

  private val instant = Instant.EPOCH
  private val realm   = Label.unsafe("myrealm")

  private val subject = User("username", realm)

  private val org               = Label.unsafe("myorg")
  private val proj              = Label.unsafe("myproj")
  private val projectRef        = ProjectRef(org, proj)
  private val defaultResolverId = Vocabulary.nxv.defaultResolver

  private val inProjectValue = InProjectValue(Priority.unsafe(42))

  private val created    = (id: Iri) =>
    ResolverCreated(
      id,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
  private val updated    = (id: Iri) =>
    ResolverUpdated(
      id,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    )
  private val tagged     = (id: Iri) =>
    ResolverTagAdded(
      id,
      projectRef,
      ResolverType.InProject,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    )
  private val deprecated = (id: Iri) =>
    ResolverDeprecated(
      id,
      projectRef,
      ResolverType.InProject,
      4,
      instant,
      subject
    )

  private val defaults  = Defaults("resolverName", "resolverDescription")
  private val injection = MigrationLogHelpers.injectResolverDefaults(defaults)

  private val expectedInProjectValue =
    InProjectValue(Some("resolverName"), Some("resolverDescription"), Priority.unsafe(42))

  test("Resolver events that need no name/desc injection should stay untouched") {
    val invariantEvents = List(tagged(defaultResolverId), deprecated(defaultResolverId))
    val injectedEvents  = invariantEvents.map(injection)
    assertEquals(injectedEvents, injectedEvents)
  }

  test("Resolver should not be injected with a name if it's not the default resolver") {
    val resolverCreatedEvent = created(iri"someId")
    val resolverUpdatedEvent = updated(iri"someId")
    val events               = List(resolverCreatedEvent, resolverUpdatedEvent)
    val injectedEvents       = events map injection
    assertEquals(injectedEvents, events)
  }

  test("Default ResolverCreated has name/desc injected") {
    val injectedEvent        = injection(created(defaultResolverId))
    val expectedCreatedEvent = ResolverCreated(
      defaultResolverId,
      projectRef,
      expectedInProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
    assertEquals(injectedEvent, expectedCreatedEvent)
  }

  test("Default ResolverUpdated has name/desc injected") {
    val injectedEvent        = injection(updated(defaultResolverId))
    val expectedCreatedEvent = ResolverUpdated(
      defaultResolverId,
      projectRef,
      expectedInProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    )
    assertEquals(injectedEvent, expectedCreatedEvent)
  }

  private val uuid        = UUID.randomUUID()
  private val orgPayload  = jsonContentOf("events/org-created.json")
  private val orgPayload2 = jsonContentOf("events/org-created-2.json")
  private val orgEvent    = ToMigrateEvent(Organizations.entityType, "id", 1L, orgPayload, Instant.EPOCH, uuid)
  private val orgEvent2   = orgEvent.copy(payload = orgPayload2)

  private lazy val orgMigrationLog =
    MigrationLog.global[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection](
      Organizations.definition(clock, UUIDF.fixed(uuid)),
      e => e.label,
      identity,
      (e, _) => e,
      EventLogConfig(QueryConfig(5, RefreshStrategy.Stop), 10.seconds),
      xas
    )

  test("Migrating a OrgCreated event") {
    orgMigrationLog(orgEvent).accepted
  }

  test("Migrating the same Org event again results in IgnoredInvalidState") {
    orgMigrationLog(orgEvent).rejectedWith[IgnoredInvalidState]
  }

  test("Trying to create an org with preexisting state fails with InvalidState") {
    orgMigrationLog(orgEvent2).rejectedWith[InvalidState[_, _]]
  }

  private lazy val resolverMigrationLog =
    MigrationLog.scoped[Iri, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection](
      Resolvers.definition((_, _, _) =>
        IO.terminate(new IllegalStateException("Resolver command evaluation should not happen"))
      )(clock),
      e => e.id,
      identity,
      (e, _) => e,
      EventLogConfig(QueryConfig(5, RefreshStrategy.Stop), 10.seconds),
      xas
    )

  private val resolverPayload  = jsonContentOf("events/resolver-created.json")
  private val resolverPayload2 = jsonContentOf("events/resolver-created-2.json")
  private val resolverEvent    = ToMigrateEvent(Resolvers.entityType, "id", 1L, resolverPayload, Instant.EPOCH, uuid)
  private val resolverEvent2   = resolverEvent.copy(payload = resolverPayload2)

  test("Migrating a ResolverCreated event") {
    resolverMigrationLog(resolverEvent).accepted
  }

  test("Migrating the same Resolver event again results in IgnoredInvalidState") {
    resolverMigrationLog(resolverEvent).rejectedWith[IgnoredInvalidState]
  }

  test("Trying to create a resolver with preexisting state fails with InvalidState") {
    resolverMigrationLog(resolverEvent2).rejectedWith[InvalidState[_, _]]
  }

}
