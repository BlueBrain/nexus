package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.fix

import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.fix.BlazegraphIndexing3266.logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCleanup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.BlazegraphViewCreated
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewType, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import monix.bio.Task

final class BlazegraphIndexing3266(
    eventLog: EventLog[Envelope[BlazegraphViewEvent]],
    views: BlazegraphViews,
    cleanup: BlazegraphIndexingCleanup,
    config: BlazegraphViewsConfig
) {

  def run(): Task[Unit] = {
    Task.delay(logger.info("Running fix for issue #3266 for blazegraph views")) >>
      eventLog
        .currentEventsByTag(BlazegraphViews.moduleTag, NoOffset)
        .evalTap {
          _.event match {
            case e: BlazegraphViewCreated if e.value.tpe == BlazegraphViewType.IndexingBlazegraphView =>
              views
                .fetchIndexingView(e.id, e.project)
                .mapError { r =>
                  new IllegalStateException(s"Failed fetching view with error ${r.reason}")
                }
                .flatMap { v =>
                  Task.unless(v.value.resourceTag.isEmpty || v.deprecated) {
                    logger.info(s"Reindexing view ${v.id} in project ${v.value.project}")
                    cleanup(IndexingBlazegraphView.resourceToViewIndex(v, config))
                  }
                }
            case _                                                                                    => Task.unit
          }
        }
        .compile
        .drain >> Task.delay(logger.info("Fix for issue #3266 for blazegraph views is now complete"))
  }

}

object BlazegraphIndexing3266 {
  private val logger: Logger = Logger[BlazegraphIndexing3266]
}
