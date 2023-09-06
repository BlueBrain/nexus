package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ResolutionResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.RootSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.JsonObject
import monix.bio.IO

object IdResolution {

  /**
    * Attempts to resolve the provided identifier across projects that the caller has access to
    *
    *   - If the query response is empty, leads to AuthorizationFailed.
    *   - If the query returns a single result, attempts to fetch the resource
    *   - If there are multiple results, they are returned as [[SearchResults]]
    *
    * @param iri
    *   identifier of the resource to be resolved
    * @param defaultViewsQuery
    *   allows to list resources
    * @param fetch
    *   function that describes how to fetch a resource
    * @param caller
    *   user having requested the resolution
    */
  def resolve(
      iri: Iri,
      defaultViewsQuery: DefaultViewsQuery.Elasticsearch,
      fetch: (ResourceRef, ProjectRef) => IO[ElasticSearchQueryError, DataResource]
  )(implicit caller: Caller): IO[ElasticSearchQueryError, Result] = {
    val locate  = ResourcesSearchParams.apply(id = Some(iri))
    val request = RootSearch(locate, Pagination.OnePage, SortList.empty)

    defaultViewsQuery.list(request).flatMap { searchResults =>
      searchResults.results match {
        case Nil         => IO.raiseError(AuthorizationFailed)
        case Seq(result) =>
          projectRefFromSource(result.source)
            .flatMap(projectRef => fetch(ResourceRef(iri), projectRef))
            .map(SingleResult)
        case _           => IO.pure(MultipleResults(searchResults))
      }
    }
  }

  // TODO: Use correct errors
  // TODO: Read _project as ProjectRef
  private def projectRefFromSource(source: JsonObject) =
    IO.fromOption(source("_project").flatMap(_.asString), AuthorizationFailed)
      .flatMap(str => IO.fromEither(ProjectRef.parse(str)).mapError(_ => AuthorizationFailed))
}

object ResolutionResponse {
  sealed trait Result
  case class SingleResult(dataResource: DataResource)                  extends Result
  case class MultipleResults(searchResults: SearchResults[JsonObject]) extends Result
}
