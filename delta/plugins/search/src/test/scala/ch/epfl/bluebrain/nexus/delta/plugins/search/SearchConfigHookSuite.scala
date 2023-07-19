package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization.defaultSearchCompositeViewFields
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
import io.circe.JsonObject
import monix.bio.Task

import java.util.UUID

class SearchConfigHookSuite extends BioSuite with CompositeViewsFixture {

  private val defaults       = Defaults("viewName", "viewDescription")
  private val indexingConfig =
    IndexingConfig(
      Set.empty,
      JsonObject.empty,
      None,
      SparqlConstructQuery.unsafe(""),
      ContextObject(JsonObject.empty),
      None
    )

  private val proj             = ProjectRef.unsafe("org", "proj")
  private val searchViewFields = defaultSearchCompositeViewFields(defaults, indexingConfig)
  private val searchViewValue  = CompositeViewValue(searchViewFields, Map.empty, Map.empty, ProjectBase.unsafe(nxv.base))
  private val otherViewValue   = CompositeViewValue(viewFields, Map.empty, Map.empty, ProjectBase.unsafe(nxv.base))

  private def searchView(value: CompositeViewValue) =
    ActiveViewDef(ViewRef(proj, defaultViewId), UUID.randomUUID(), 1, value)

  private def otherView(value: CompositeViewValue) =
    ActiveViewDef(ViewRef(proj, nxv + "not-a-search-view"), UUID.randomUUID(), 1, value)

  private def searchConfigHook =
    Ref.of[Task, Option[ViewRef]](None).map { r =>
      new SearchConfigHook(defaults, indexingConfig, (v, _) => r.set(Some(v.ref)).hideErrors) -> r.get.hideErrors
    }

  test("A default view whose sources do not match the current config should be updated") {
    for {
      (hook, updated) <- searchConfigHook
      view            <- otherViewValue.map(searchView)
      _               <- hook.apply(view).getOrElse(Task.delay(println("plop")))
      _               <- updated.assertSome(ViewRef(proj, defaultViewId))
    } yield ()
  }

  test("A non-default active view should not be updated") {
    for {
      (hook, updated) <- searchConfigHook
      view            <- otherViewValue.map(otherView)
      _               <- hook.apply(view).getOrElse(Task.unit)
      _               <- updated.assertNone
    } yield ()
  }

  test("A default view whose sources match the current config should not be updated") {
    for {
      (hook, updated) <- searchConfigHook
      view            <- searchViewValue.map(searchView)
      _               <- hook.apply(view).getOrElse(Task.unit)
      _               <- updated.assertNone
    } yield ()
  }

}
