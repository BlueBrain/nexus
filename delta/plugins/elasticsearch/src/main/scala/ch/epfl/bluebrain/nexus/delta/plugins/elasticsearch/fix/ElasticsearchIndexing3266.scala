package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.fix

import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.fix.ElasticsearchIndexing3266.logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCleanup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.ElasticSearchViewCreated
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import monix.bio.Task

final class ElasticsearchIndexing3266(
    eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
    views: ElasticSearchViews,
    cleanup: ElasticSearchIndexingCleanup,
    config: ElasticSearchViewsConfig
) {

  def run(): Task[Unit] = {
    Task.delay(logger.info("Running fix for issue #3266 for elasticsearch views")) >>
      eventLog
        .currentEventsByTag(ElasticSearchViews.moduleTag, NoOffset)
        .evalTap {
          _.event match {
            case e: ElasticSearchViewCreated if e.value.tpe == ElasticSearchViewType.ElasticSearch =>
              views
                .fetchIndexingView(e.id, e.project)
                .mapError { r =>
                  new IllegalStateException(s"Failed fetching view with error ${r.reason}")
                }
                .flatMap { v =>
                  Task.unless(v.value.resourceTag.isEmpty || v.deprecated) {
                    logger.info(s"Reindexing view ${v.id} in project ${v.value.project}")
                    cleanup(IndexingElasticSearchView.resourceToViewIndex(v, config))
                  }
                }
            case _                                                                                 => Task.unit
          }
        }
        .compile
        .drain >> Task.delay(logger.info("Fix for issue #3266 for elasticsearch views is now complete"))
  }

}

object ElasticsearchIndexing3266 {
  private val logger: Logger = Logger[ElasticsearchIndexing3266]
}
