package ai.senscience.nexus.delta.plugins.search

import ai.senscience.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ai.senscience.nexus.delta.plugins.search.model.defaultViewId
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViewFactory, CompositeViewsFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectBase
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.{Json, JsonObject}

import java.util.UUID
import scala.concurrent.duration.*

class SearchConfigHookSuite extends NexusSuite with CompositeViewsFixture {

  implicit private val projectBase: ProjectBase = ProjectBase(nxv.base)

  private val defaults      = Defaults("viewName", "viewDescription")
  private val currentConfig =
    IndexingConfig(
      resourceTypes = IriFilter.restrictedTo(nxv + "Test"),
      mapping = JsonObject("mapping" -> Json.obj()),
      settings = Some(JsonObject("settings" -> Json.obj())),
      query = SparqlConstructQuery.unsafe("query"),
      context = ContextObject(JsonObject("context" -> Json.obj())),
      rebuildStrategy = Some(CompositeView.Interval(30.seconds))
    )

  private val proj = ProjectRef.unsafe("org", "proj")

  // Create a search view from the provided config
  private def searchView(defaults: Defaults, config: IndexingConfig): IO[ActiveViewDef] = {
    val fields             = SearchViewFactory(defaults, config)
    val compositeViewValue = CompositeViewFactory.create(fields)
    compositeViewValue.map { value =>
      ActiveViewDef(ViewRef(proj, defaultViewId), UUID.randomUUID(), 1, value)
    }
  }

  // Create the hook with a current config
  private def execSearchHook(view: ActiveViewDef) =
    Ref
      .of[IO, Option[ViewRef]](None)
      .map { r =>
        new SearchConfigHook(defaults, currentConfig, (v, _) => r.set(Some(v.ref))) -> r.get
      }
      .flatMap { case (hook, updatedRef) =>
        hook(view).getOrElse(IO.unit).as(updatedRef)
      }

  private def assertViewUpdated(view: IO[ActiveViewDef]) =
    for {
      v       <- view
      updated <- execSearchHook(v)
      _       <- updated.assertEquals(Some(v.ref))
    } yield ()

  private def assertViewNotUpdated(view: IO[ActiveViewDef]) =
    for {
      v       <- view
      updated <- execSearchHook(v)
      _       <- updated.assertEquals(None)
    } yield ()

  test("A search view should be updated when the name is updated") {
    val previousDefaults = defaults.copy(name = "Old name")
    assertViewUpdated(searchView(previousDefaults, currentConfig))
  }

  test("A search view should be updated when the description is updated") {
    val previousDefaults = defaults.copy(description = "Old description")
    assertViewUpdated(searchView(previousDefaults, currentConfig))
  }

  test("A search view should be updated when the construct query is updated") {
    val previousConfig = currentConfig.copy(query = SparqlConstructQuery.unsafe("old query"))
    assertViewUpdated(searchView(defaults, previousConfig))
  }

  test("A search view should be updated when Elasticsearch mappings are updated") {
    val previousConfig = currentConfig.copy(mapping = JsonObject("old-mappings" -> Json.obj()))
    assertViewUpdated(searchView(defaults, previousConfig))
  }

  test("A search view should be updated when Elasticsearch settings are updated") {
    val previousConfig = currentConfig.copy(settings = Some(JsonObject("old-settings" -> Json.obj())))
    assertViewUpdated(searchView(defaults, previousConfig))
  }

  test("A search view should be updated when context is updated") {
    val previousConfig = currentConfig.copy(context = ContextObject(JsonObject("old-context" -> Json.obj())))
    assertViewUpdated(searchView(defaults, previousConfig))
  }

  test("A search view should be updated when resource types are updated") {
    val previousConfig =
      currentConfig.copy(resourceTypes = IriFilter.fromSet(Set(nxv + "Test", nxv + "NewType")))
    assertViewUpdated(searchView(defaults, previousConfig))
  }

  test("A non-default active view (!= id) should not be updated") {
    assertViewNotUpdated {
      val fields = SearchViewFactory(defaults, currentConfig)
      CompositeViewFactory.create(fields).map { value =>
        ActiveViewDef(ViewRef(proj, nxv + "not-a-search-view"), UUID.randomUUID(), 1, value)
      }
    }
  }

  test("A default view whose who has been created with the same config should not be updated") {
    assertViewNotUpdated(searchView(defaults, currentConfig))
  }

}
