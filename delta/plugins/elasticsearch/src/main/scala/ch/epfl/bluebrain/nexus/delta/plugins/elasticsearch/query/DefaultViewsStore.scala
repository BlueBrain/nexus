package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions, ElasticSearchViewState}
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import doobie._
import doobie.implicits._
import io.circe.{Decoder, Json}
import monix.bio.{IO, UIO}

/**
  * Store to retrieve default elasticsearch views
  */
trait DefaultViewsStore {

  /**
    * Return views at the given scope
    */
  def find(scope: Scope): UIO[List[IndexingView]]
}

object DefaultViewsStore {

  import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

  private[query] def asIndexingView(prefix: String, state: ElasticSearchViewState) =
    IndexingView(
      ViewRef(state.project, state.id),
      ElasticSearchViews.index(state.uuid, state.indexingRev, prefix).value,
      // Default views always require the `resources/read` permission
      permissions.read
    )

  def apply(prefix: String, xas: Transactors): DefaultViewsStore = {
    new DefaultViewsStore {
      implicit val stateDecoder: Decoder[ElasticSearchViewState] = ElasticSearchViewState.serializer.codec
      def find(scope: Scope): UIO[List[IndexingView]]            =
        (fr"SELECT value FROM scoped_states" ++
          Fragments.whereAndOpt(
            Some(fr"type = ${ElasticSearchViews.entityType}"),
            scope.asFragment,
            Some(fr"tag = ${Tag.Latest.value}"),
            Some(fr"id = $defaultViewId"),
            Some(fr"deprecated = false")
          ))
          .query[Json]
          .to[List]
          .transact(xas.read)
          .flatMap { rows =>
            rows.traverse { r =>
              IO.fromEither(r.as[ElasticSearchViewState]).map(asIndexingView(prefix, _))
            }
          }
          .hideErrors
    }
  }
}
