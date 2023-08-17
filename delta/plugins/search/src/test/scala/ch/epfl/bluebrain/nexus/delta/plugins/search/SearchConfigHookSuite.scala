package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViewFactory, CompositeViewsFixture}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectBase
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.{Json, JsonObject}
import monix.bio.{Task, UIO}

import java.util.UUID
import scala.concurrent.duration._

class SearchConfigHookSuite extends BioSuite with CompositeViewsFixture {

  implicit private val projectBase: ProjectBase = ProjectBase.unsafe(nxv.base)

  private val defaults      = Defaults("viewName", "viewDescription")
  private val currentConfig =
    IndexingConfig(
      resourceTypes = Set(nxv + "Test"),
      mapping = JsonObject("mapping" -> Json.obj()),
      settings = Some(JsonObject("settings" -> Json.obj())),
      query = SparqlConstructQuery.unsafe("query"),
      context = ContextObject(JsonObject("context" -> Json.obj())),
      rebuildStrategy = Some(CompositeView.Interval(30.seconds))
    )

  private val proj = ProjectRef.unsafe("org", "proj")

  // Create a search view from the provided config
  private def searchView(defaults: Defaults, config: IndexingConfig) = {
    val fields = SearchViewFactory(defaults, config)
    CompositeViewFactory.create(fields).map { value =>
      ActiveViewDef(ViewRef(proj, defaultViewId), UUID.randomUUID(), 1, value)
    }
  }

  // Create the hook with a current config
  private def execSearchHook(view: ActiveViewDef) =
    Ref
      .of[Task, Option[ViewRef]](None)
      .map { r =>
        new SearchConfigHook(defaults, currentConfig, (v, _) => r.set(Some(v.ref)).hideErrors) -> r.get.hideErrors
      }
      .flatMap { case (hook, updatedRef) =>
        hook(view).getOrElse(Task.unit).as(updatedRef)
      }

  private def assertViewUpdated(view: UIO[ActiveViewDef]) =
    for {
      v       <- view
      updated <- execSearchHook(v)
      _       <- updated.assertSome(v.ref)
    } yield ()

  private def assertViewNotUpdated(view: UIO[ActiveViewDef]) =
    for {
      v       <- view
      updated <- execSearchHook(v)
      _       <- updated.assertNone
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
    val previousConfig = currentConfig.copy(resourceTypes = Set(nxv + "Test", nxv + "NewType"))
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
