package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.BlazegraphViewsCache
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewType, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, IndexingAction}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import monix.bio.IO

class BlazegraphIndexingAction(
    client: BlazegraphClient,
    cache: BlazegraphViewsCache,
    indexingConfig: ExternalIndexingConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri)
    extends IndexingAction {
  override protected def execute(
      project: ProjectRef,
      res: EventExchange.EventExchangeValue[_, _]
  ): IO[ServiceError.IndexingActionFailed, Unit] = {
    for {
      projectViews <- cache.values(project).map {
                        _.mapFilter {
                          case v: ViewResource
                              if v.value.tpe == BlazegraphViewType.IndexingBlazegraphView && !v.deprecated =>
                            val indexing = v.map(_.asInstanceOf[IndexingBlazegraphView])
                            Option.when(indexing.value.resourceTag.isEmpty)(indexing)
                          case _ => None
                        }
                      }
      streamEntry  <- BlazegraphIndexingStreamEntry.fromEventExchange(res)
      queries      <- projectViews
                        .traverse { v =>
                          streamEntry
                            .writeOrNone(v.value)
                            .map(_.map(q => (BlazegraphViews.namespace(v, indexingConfig), q)))
                        }
                        .map(_.flatten)
      _            <- queries.parTraverse { case (index, query) =>
                        client.bulk(index, Seq(query))
                      }
    } yield ()
  }.mapError(err => IndexingFailed(err.getMessage, res.value.resource.void))
}
