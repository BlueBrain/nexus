package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchConfigUpdater.logger
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization._
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{SearchConfig, defaultViewId}
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}

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
  )(implicit baseUri: BaseUri): Task[Unit] =
    views
      .filter(_.id == defaultViewId)
      .evalTap { elem =>
        elem.traverse {
          case view: ActiveViewDef if configHasChanged(view) =>
            update(view, defaultSearchViewFields)
          case _                                             =>
            Task.unit
        }
      }
      .compile
      .drain
      .doOnFinish {
        case Some(cause) => UIO.delay(logger.error("Updating default composite views failed.", cause.toThrowable))
        case None        => UIO.delay(logger.info("Stopping stream. All default composite views have been updated."))
      }

  private def configHasChanged(v: ActiveViewDef)(implicit baseUri: BaseUri): Boolean =
    CompositeViewFields.fromValue(v.value).toJson(v.ref.viewId) != defaultSearchViewFields.toJson(v.ref.viewId)

  private def defaultSearchViewFields: CompositeViewFields =
    defaultSearchCompositeViewFields(defaults, config)

}

object SearchConfigUpdater {

  private val logger: Logger = Logger[SearchConfigUpdater]

  /**
    * Creates a [[SearchConfigUpdater]] and returns the [[Task]] that updates all default composite view that are not in
    * line with the given search config.
    */
  def apply(
      compositeViews: CompositeViews,
      config: SearchConfig
  )(implicit
      baseUri: BaseUri,
      subject: Subject
  ): Task[SearchConfigUpdater] = {
    val updater = new SearchConfigUpdater(config.defaults, config.indexing)
    updater(compositeViews.currentViews, update(compositeViews))
      .as(updater)
  }

  private def update(
      views: CompositeViews
  )(implicit
      subject: Subject,
      baseUri: BaseUri
  ): (ActiveViewDef, CompositeViewFields) => Task[Unit] =
    (viewDef, fields) =>
      views
        .update(
          IdSegment.IriSegment(viewDef.ref.viewId),
          viewDef.ref.project,
          viewDef.rev,
          fields
        )
        .void
        .onErrorHandle(e => logger.error(s"Could not update view ${viewDef.ref}", e))
}
