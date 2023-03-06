package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverType}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.Json

import java.time.Instant

class MigrationLogSuite extends BioSuite {

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

}
