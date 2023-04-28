package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization.searchGroup
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{defaultProjectionId, defaultSourceId, defaultViewId}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import fs2.Stream
import io.circe.JsonObject
import monix.bio.Task

import java.time.Instant
import scala.collection.mutable.{Set => MutableSet}

class SearchConfigUpdaterSuite extends BioSuite with CompositeViewsFixture with Fixtures {

  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val indexingConfig =
    IndexingConfig(
      Set.empty,
      JsonObject.empty,
      None,
      SparqlConstructQuery.unsafe(""),
      ContextObject(JsonObject.empty),
      None
    )

  private val defaults              = Defaults("viewName", "viewDescription")
  private val defaultEsProjection   = esProjection.copy(
    id = defaultProjectionId,
    query = indexingConfig.query,
    mapping = indexingConfig.mapping,
    indexGroup = searchGroup,
    context = indexingConfig.context,
    settings = indexingConfig.settings,
    resourceTypes = indexingConfig.resourceTypes
  )
  private val modifiedEsProjection  = defaultEsProjection.copy(resourceTypes = Set(iri"https://schema.org/Person"))
  private val compositeValue        = CompositeViewValue(
    Some(defaults.name),
    Some(defaults.description),
    NonEmptySet.of(projectSource.copy(id = defaultSourceId)),
    NonEmptySet.of(defaultEsProjection),
    None
  )
  private val modifiedCompositeView = compositeValue.copy(projections = NonEmptySet.of(modifiedEsProjection))

  private val proj1 = ProjectRef.unsafe("org", "proj1")
  private val proj2 = ProjectRef.unsafe("org", "proj2")
  private val proj3 = ProjectRef.unsafe("org", "proj3")
  private val rev   = 1

  private val upToDateView        = ActiveViewDef(ViewRef(proj1, defaultViewId), uuid, rev, compositeValue)
  private val defaultOutdatedView = ActiveViewDef(ViewRef(proj2, defaultViewId), uuid, rev, modifiedCompositeView)
  private val customOutdatedView  = ActiveViewDef(ViewRef(proj2, id), uuid, rev, modifiedCompositeView)
  private val deprecatedView      = DeprecatedViewDef(ViewRef(proj3, defaultViewId))

  private val views        =
    Stream.emits(Seq(upToDateView, defaultOutdatedView, customOutdatedView, deprecatedView)).zipWithIndex.map {
      case (v, index) =>
        SuccessElem(
          tpe = CompositeViews.entityType,
          id = v.ref.viewId,
          project = Some(v.ref.project),
          instant = Instant.EPOCH,
          offset = Offset.at(index),
          value = v,
          rev = 1
        )
    }
  private val updatedViews = MutableSet.empty[(CompositeViewDef, CompositeViewFields)]
  implicit private class CompositeViewTestOps(x: (CompositeViewDef, CompositeViewFields)) {
    def viewDef: CompositeViewDef = x._1
  }

  private val update: (ActiveViewDef, CompositeViewFields) => Task[Unit] =
    (viewDef, fields) => Task { updatedViews.add((viewDef, fields)) }.void

  test("Update the views") {
    new SearchConfigUpdater(defaults, indexingConfig, views, update)().compile.drain
  }

  test("A default active view whose sources do not match the current config should be updated") {
//    assert(updatedViews.contains((defaultOutdatedView, fromValue(upToDateView.value))))
    assert(updatedViews.exists(_._1 == defaultOutdatedView))
  }

  test("A default active view whose sources match the current config should not be updated") {
    assert(!updatedViews.exists(_.viewDef == upToDateView))
  }

  test("A non-default active view should not be updated") {
    assert(!updatedViews.exists(_.viewDef == customOutdatedView))
  }

  test("Deprecated view should not be updated") {
    assert(!updatedViews.exists(_.viewDef == deprecatedView))
  }

}
