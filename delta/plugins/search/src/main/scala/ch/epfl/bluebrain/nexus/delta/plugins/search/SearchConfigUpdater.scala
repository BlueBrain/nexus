package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.Eq
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue.indexingEq
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization._
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{defaultViewId, SearchConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task

/**
  * Allows to update the search config of default composite views. The provided defaults and indexing config provide the
  * basis of the [[CompositeViewFields]] to which the composite views are compared.
  */
final class SearchConfigUpdater(defaults: Defaults, config: IndexingConfig) {

  /**
    * For the given composite views, updates the active ones if their search config differs from the current one.
    * @param views
    *   a stream of views to perform the update on
    * @param update
    *   a function that defines what update should be done to an active view
    */
  def apply(
      views: ElemStream[CompositeViewDef],
      update: (ActiveViewDef, CompositeViewFields) => Task[Unit]
  ): Stream[Task, Elem[CompositeViewDef]] =
    views
      .evalTap { elem =>
        elem.traverse {
          case view: ActiveViewDef if viewIsDefault(view) && configHasChanged(view) =>
            update(view, defaultSearchViewFields)
          case _                                                                    =>
            Task.unit
        }
      }

  private def configHasChanged(
      v: ActiveViewDef
  )(implicit eq: Eq[CompositeViewValue] = indexingEq): Boolean =
    v.value =!= defaultSearchViewValue(v)

  private def viewIsDefault(v: ActiveViewDef): Boolean =
    v.ref.viewId == defaultViewId

  private def defaultSearchViewValue(v: ActiveViewDef): CompositeViewValue = {
    val d = defaultSearchCompositeViewFields(defaults, config)
    CompositeViewValue(
      d.name,
      d.description,
      d.sources.map(_.toSource(v.uuid, v.ref.viewId)),
      d.projections.map(_.toProjection(v.uuid, v.ref.viewId)),
      d.rebuildStrategy
    )
  }

  private def defaultSearchViewFields: CompositeViewFields =
    defaultSearchCompositeViewFields(defaults, config)

}

object SearchConfigUpdater {

  private val logger: Logger = Logger[SearchConfigUpdater]
  private val metadata       = ProjectionMetadata("system", "search-config-updater", None, None)

  /**
    * Creates a [[SearchConfigUpdater]] and returns the [[Task]] that updates all default composite view that are not in
    * line with the given search config.
    */
  def apply(
      supervisor: Supervisor,
      compositeViews: CompositeViews,
      config: SearchConfig
  )(implicit
      baseUri: BaseUri,
      subject: Subject
  ): Task[SearchConfigUpdater] = {
    val updater = new SearchConfigUpdater(config.defaults, config.indexing)
    val stream  = updater(compositeViews.currentViews, update(compositeViews)).drain

    supervisor
      .run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => stream))
      .as(updater)
  }

  private[search] def update(
      views: CompositeViews
  )(implicit
      subject: Subject,
      baseUri: BaseUri
  ): (ActiveViewDef, CompositeViewFields) => Task[Unit] =
    (viewDef, fields) =>
      views
        .update(
          viewDef.ref.viewId,
          viewDef.ref.project,
          viewDef.rev,
          fields
        )
        .void
        .onErrorHandle(e => logger.error(s"Could not update view ${viewDef.ref}. Reason: ${e.reason}"))
}
