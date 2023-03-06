package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant
import java.util.UUID

class BlazegraphMigrationLogSuite extends BioSuite {

  private val uuid           = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant        = Instant.EPOCH
  private val subject        = User("username", Label.unsafe("myrealm"))
  private val tag            = UserTag.unsafe("mytag")
  private val projectRef     = ProjectRef.unsafe("myorg", "myproj")
  private val indexingValue  = IndexingBlazegraphViewValue()
  private val indexingSource = indexingValue.toJson _

  // format: off
  private val created = (id: Iri) => BlazegraphViewCreated(id, projectRef, uuid, indexingValue, indexingSource(id), 1, instant, subject)
  private val updated = (id: Iri) => BlazegraphViewUpdated(id, projectRef, uuid, indexingValue, indexingSource(id), 2, instant, subject)
  private val tagged = (id: Iri) => BlazegraphViewTagAdded(id, projectRef, IndexingBlazegraphView, uuid, targetRev = 1, tag, 3, instant, subject)
  private val deprecated = (id: Iri) => BlazegraphViewDeprecated(id, projectRef, IndexingBlazegraphView, uuid, 4, instant, subject)
  // format: on

  private val defaults  = Defaults("bgName", "bgDescription")
  private val injection = BlazegraphPluginModule.injectBlazegraphViewDefaults(defaults)

  test("BG view events that need no name/desc injection should stay untouched") {
    val invariantEvents = List(tagged(defaultViewId), deprecated(defaultViewId))
    val injectedEvents  = invariantEvents.map(injection)
    assertEquals(injectedEvents, invariantEvents)
  }

  test("BG view event should not be injected with a name if it's not the default view") {
    val bgViewCreatedEvent = created(iri"someId")
    val bgViewUpdatedEvent = updated(iri"someId")
    val events             = List(bgViewCreatedEvent, bgViewUpdatedEvent)
    val injectedEvents     = events.map(injection)
    assertEquals(injectedEvents, events)
  }

  private val expectedIndexingValue =
    IndexingBlazegraphViewValue(Some("bgName"), Some("bgDescription"))

  test("Default BlazegraphViewCreated has name/desc injected") {
    val injectedEvent = injection(created(defaultViewId))
    val expected      = BlazegraphViewCreated(
      defaultViewId,
      projectRef,
      uuid,
      expectedIndexingValue,
      indexingSource(defaultViewId),
      1,
      instant,
      subject
    )
    assertEquals(injectedEvent, expected)
  }

  test("Default BlazegraphViewUpdated has name/desc injected") {
    val injectedEvent = injection(updated(defaultViewId))
    val expected      = BlazegraphViewUpdated(
      defaultViewId,
      projectRef,
      uuid,
      expectedIndexingValue,
      indexingSource(defaultViewId),
      2,
      instant,
      subject
    )
    assertEquals(injectedEvent, expected)
  }

}
