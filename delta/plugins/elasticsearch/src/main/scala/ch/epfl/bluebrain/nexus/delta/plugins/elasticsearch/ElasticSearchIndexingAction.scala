package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.ElasticSearchViewCache
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{DataEncoder, ElasticSearchIndexingStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.{IndexingActionFailed, IndexingFailed}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{Pipe, PipeConfig}
import monix.bio.IO

class ElasticSearchIndexingAction(
    client: ElasticSearchClient,
    cache: ElasticSearchViewCache,
    pipeConfig: PipeConfig,
    config: ElasticSearchViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri)
    extends IndexingAction {
  override protected def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit] =
    (for {
      projectViews <- cache.values(project).map { vs =>
                        vs.filter(v => v.value.tpe == ElasticSearchViewType.ElasticSearch && !v.deprecated)
                          .map(_.map(_.asInstanceOf[IndexingElasticSearchView]))
                      }
      queries      <- projectViews
                        .traverseFilter { v =>
                          def encoder = DataEncoder.defaultEncoder(v.value.context)
                          Pipe.run(v.value.pipeline, pipeConfig).flatMap { pipeline =>
                            ElasticSearchIndexingStream.process(
                              res,
                              IndexLabel.fromView(config.indexing.prefix, v.value.uuid, v.rev),
                              pipeline,
                              encoder
                            )
                          }
                        }
      _            <- client.bulk(queries, config.syncIndexingRefresh)
    } yield ()).mapError(err => IndexingFailed(err.getMessage, res.value.resource.void))
}
