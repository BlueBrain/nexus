package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

class CompositeViewMigrationLogSuite extends SerializationSuite with CompositeViewsFixture {

  test("ElasticSearchProjection that already have an includeContext field are left untouched") {
    val json         = jsonContentOf("composite-views/migration/view-created-with-include-context.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(json)
  }

  test("An ElasticSearchProjection has includeContext field injected if it doesn't exist") {
    val json         = jsonContentOf("composite-views/migration/view-created-no-include-context.json")
    val expectedJson = jsonContentOf("composite-views/migration/view-created-injected.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(expectedJson)
  }

  test("If a CompositeViewEvent doesn't have a value field, it is left unchanged") {
    val json         = jsonContentOf("composite-views/database/view-deprecated.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(json)
  }

  private val viewSource = Json.Null
  private val viewId     = iri"http://example.com/composite-view"
  private val tag        = UserTag.unsafe("mytag")

  private val defaultSearchViewId          =
    iri"https://bluebrain.github.io/nexus/vocabulary/searchView"
  // Name and description need to match the values in the search config!
  private val defaultSearchViewName        = "Default search view"
  private val defaultSearchViewDescription =
    "A search view of all resources in the project."

  private val created    = id => CompositeViewCreated(id, project.ref, uuid, viewValue, viewSource, 1, epoch, subject)
  private val updated    = id => CompositeViewUpdated(id, project.ref, uuid, viewValue, viewSource, 2, epoch, subject)
  private val tagged     = id => CompositeViewTagAdded(id, projectRef, uuid, targetRev = 1, tag, 3, epoch, subject)
  private val deprecated = id => CompositeViewDeprecated(id, projectRef, uuid, 4, epoch, subject)

  private val injection = CompositeViewsPluginModule.injectSearchViewDefaults

  private val expectedCompositeViewValue =
    viewValue.copy(name = Some(defaultSearchViewName), description = Some(defaultSearchViewDescription))

  test("CompositeViewEvents that needs no name/desc injection remain unchanged") {
    val invariantEvents =
      List(tagged(defaultSearchViewId), tagged(viewId), deprecated(defaultSearchViewId), deprecated(viewId))
    val injectedEvents  = invariantEvents.map(injection)
    assertEquals(injectedEvents, invariantEvents)
  }

  test("CompositeViewEvents should have no name/desc injection if it is not the default SearchView") {
    val invariantEvents = List(created(viewId), updated(viewId))
    val injectedEvents  = invariantEvents.map(injection)
    assertEquals(injectedEvents, invariantEvents)
  }

  test("CompositeViewCreated has name/desc injected for the default SearchView") {
    val injectedEvent = injection(created(defaultSearchViewId))
    val expectedEvent = CompositeViewCreated(
      defaultSearchViewId,
      projectRef,
      uuid,
      expectedCompositeViewValue,
      viewSource,
      1,
      epoch,
      subject
    )
    assertEquals(injectedEvent, expectedEvent)
  }

  test("CompositeViewUpdated has name/desc injected for the default SearchView") {
    val injectedEvent = injection(updated(defaultSearchViewId))
    val expectedEvent = CompositeViewUpdated(
      defaultSearchViewId,
      projectRef,
      uuid,
      expectedCompositeViewValue,
      viewSource,
      2,
      epoch,
      subject
    )
    assertEquals(injectedEvent, expectedEvent)
  }

}
