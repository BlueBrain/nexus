package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.ElasticSearchViewCache
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.{IndexingActionFailed, IndexingFailed}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

class ElasticSearchIndexingAction(
    client: ElasticSearchClient,
    cache: ElasticSearchViewCache,
    config: ElasticSearchViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri)
    extends IndexingAction {
  override protected def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit] =
    (for {
      projectViews <- cache.get(project).map { vs =>
                        vs.filter(v => v.value.tpe == ElasticSearchViewType.ElasticSearch && !v.deprecated)
                          .map(_.map(_.asInstanceOf[IndexingElasticSearchView]))
                      }

      streamEntry <- ElasticSearchIndexingStreamEntry.fromEventExchange(res)
      queries     <- projectViews
                       .traverse { v =>
                         streamEntry.writeOrNone(IndexLabel.fromView(config.indexing.prefix, v.value.uuid, v.rev), v.value)
                       }
                       .map(_.flatten)
      _           <- client.bulk(queries, config.syncIndexingRefresh)
    } yield ()).mapError(err => IndexingFailed(err.getMessage, res.value.resource.void))
}
