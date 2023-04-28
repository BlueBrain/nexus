package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.Eq
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue.indexingEq
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchConfigUpdater.logger
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization._
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}

/**
  * Allows to update the search config of default composite views. The provided defaults and indexing config provide the
  * basis of the [[CompositeViewValue]] to which the composite views are compared.
  *
  * @param defaults
  *   contains the name & description for the view to update
  * @param config
  *   the indexing config that is the basis of the comparison to decide whether a view needs an update
  * @param views
  *   a stream of views to perform the update on
  * @param update
  *   a function that defines what update should be done to an active view
  */
final class SearchConfigUpdater(
    defaults: Defaults,
    config: IndexingConfig,
    views: ElemStream[CompositeViewDef],
    update: (ActiveViewDef, CompositeViewFields) => UIO[Unit]
) {

  /**
    * For the given composite views, updates the active ones if their search config differs from the current one.
    */
  def apply(): Task[Unit] =
    Task.delay(logger.info("Starting the SearchConfigUpdater.")) >>
      views
        .evalTap { elem =>
          elem.traverse {
            case view: ActiveViewDef if viewIsDefault(view) && configHasChanged(view) =>
              update(view, defaultSearchCompositeViewFields(defaults, config))
            case _                                                                    =>
              Task.unit
          }
        }
        .compile
        .drain >>
      Task.delay(logger.info("Reached the end of composite views. Stopping the SearchConfigUpdater."))

  private def configHasChanged(v: ActiveViewDef): Boolean = {
    implicit val eq: Eq[CompositeViewValue] = indexingEq
    v.value =!= defaultSearchViewValue(v)
  }

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
      defaults: Defaults,
      indexingConfig: IndexingConfig
  )(implicit
      baseUri: BaseUri,
      subject: Subject
  ): Task[SearchConfigUpdater] = {
    val updater = new SearchConfigUpdater(
      defaults,
      indexingConfig,
      compositeViews.currentViews,
      update(compositeViews)
    )
    val stream  = Stream.emit(1).evalTap(_ => updater()).drain

    supervisor
      .run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => stream))
      .as(updater)
  }

  private[search] def update(
      views: CompositeViews
  )(implicit
      subject: Subject,
      baseUri: BaseUri
  ): (ActiveViewDef, CompositeViewFields) => UIO[Unit] =
    (viewDef, fields) =>
      views
        .update(
          viewDef.ref.viewId,
          viewDef.ref.project,
          viewDef.rev,
          fields
        )
        .void
        .onErrorHandleWith(e => UIO.delay(logger.error(s"Could not update view ${viewDef.ref}. Reason: ${e.reason}")))
}
