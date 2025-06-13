package ai.senscience.nexus.delta.plugins.search

import ai.senscience.nexus.delta.plugins.search.model.SearchRejection.UnknownSuite
import ai.senscience.nexus.delta.plugins.search.model.{defaultProjectionId, defaultViewId, SearchConfig}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Project as ProjectAcl
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.{Json, JsonObject}
import org.http4s.Query

trait Search {

  /**
    * Queries all the underlying search indices that the ''caller'' has access to
    *
    * @param payload
    *   the query payload
    */
  def query(payload: JsonObject, qp: Query)(implicit caller: Caller): IO[Json]

  /**
    * Queries the underlying search indices for the provided suite that the ''caller'' has access to
    *
    * @param suite
    *   the suite where the search query has to be applied
    * @param payload
    *   the query payload
    */
  def query(suite: Label, additionalProjects: Set[ProjectRef], payload: JsonObject, qp: Query)(implicit
      caller: Caller
  ): IO[Json]
}

object Search {

  final case class TargetProjection(projection: ElasticSearchProjection, view: CompositeView)

  private[search] type ListProjections = () => IO[Seq[TargetProjection]]
  private[search] type ExecuteSearch   = (JsonObject, Set[String], Query) => IO[Json]

  /**
    * Constructs a new [[Search]] instance.
    */
  final def apply(
      compositeViews: CompositeViews,
      aclCheck: AclCheck,
      client: ElasticSearchClient,
      prefix: String,
      suites: SearchConfig.Suites
  ): Search = {

    val listProjections: ListProjections = () =>
      compositeViews
        .list(
          Pagination.OnePage,
          CompositeViewSearchParams(deprecated = Some(false), filter = v => IO.pure(v.id == defaultViewId)),
          Ordering.by(_.createdAt)
        )
        .map(
          _.results
            .flatMap { entry =>
              val res = entry.source
              for {
                projection   <- res.value.projections.lookup(defaultProjectionId)
                esProjection <- projection.asElasticSearch
              } yield TargetProjection(esProjection, res.value)
            }
        )
    val executeSearch: ExecuteSearch     = client.search(_, _, _)()
    apply(listProjections, aclCheck, executeSearch, prefix, suites)
  }

  /**
    * Constructs a new [[Search]] instance.
    */
  final def apply(
      listProjections: ListProjections,
      aclCheck: AclCheck,
      executeSearch: ExecuteSearch,
      prefix: String,
      suites: SearchConfig.Suites
  ): Search =
    new Search {

      private def query(projectionPredicate: TargetProjection => Boolean, payload: JsonObject, qp: Query)(implicit
          caller: Caller
      ) =
        for {
          allProjections    <- listProjections().map(_.filter(projectionPredicate))
          accessibleIndices <- aclCheck.mapFilter[TargetProjection, String](
                                 allProjections,
                                 p => ProjectAcl(p.view.project) -> p.projection.permission,
                                 p => projectionIndex(p.projection, p.view.uuid, prefix).value
                               )
          results           <- executeSearch(payload, accessibleIndices, qp).adaptError { case e: ElasticSearchClientError =>
                                 WrappedElasticSearchClientError(e)
                               }
        } yield results

      override def query(payload: JsonObject, qp: Query)(implicit caller: Caller): IO[Json] =
        query(_ => true, payload, qp)

      override def query(suite: Label, additionalProjects: Set[ProjectRef], payload: JsonObject, qp: Query)(implicit
          caller: Caller
      ): IO[Json] = {
        IO.fromOption(suites.get(suite))(UnknownSuite(suite)).flatMap { suiteProjects =>
          val allProjects                             = suiteProjects ++ additionalProjects
          def predicate(p: TargetProjection): Boolean = allProjects.contains(p.view.project)
          query(predicate(_), payload, qp)
        }
      }

    }
}
