package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.RootSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
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
      fetch: (ResourceRef, ProjectRef) => IO[ResourceRejection, DataResource]
  )(implicit caller: Caller): IO[ElasticSearchQueryError, Result] = {
    val locate  = ResourcesSearchParams.apply(id = Some(iri))
    val request = RootSearch(locate, Pagination.OnePage, SortList.empty)

    defaultViewsQuery.list(request).flatMap { searchResults =>
      searchResults.results match {
        case Nil         => IO.raiseError(AuthorizationFailed)
        case Seq(result) =>
          projectRefFromSource(result.source)
            .flatMap(projectRef => fetch(ResourceRef(iri), projectRef))
            .mapError(_ => AuthorizationFailed) // TODO: Map to better error
            .map(SingleResult)
        case _           => IO.pure(MultipleResults(searchResults))
      }
    }
  }

  // TODO: Use correct error
  private def projectRefFromSource(source: JsonObject) =
    IO.fromOption(
      source("_project")
        .flatMap(_.as[Iri].toOption)
        .flatMap(projectRefFromIri),
      AuthorizationFailed
    )

  private val projectRefRegex =
    s"^.+/projects/(${Label.regex.regex})/(${Label.regex.regex})".r

  private def projectRefFromIri(iri: Iri) =
    iri.toString match {
      case projectRefRegex(org, proj) =>
        Some(ProjectRef(Label.unsafe(org), Label.unsafe(proj)))
      case _                          =>
        None
    }
}

object IdResolutionResponse {
  sealed trait Result
  case class SingleResult(dataResource: DataResource)                  extends Result
  case class MultipleResults(searchResults: SearchResults[JsonObject]) extends Result
}
