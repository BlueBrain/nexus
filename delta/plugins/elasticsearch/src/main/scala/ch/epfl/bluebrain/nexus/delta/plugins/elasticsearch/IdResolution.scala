package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.RootSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

import java.rmi.UnexpectedException

/**
  * @param defaultViewsQuery
  *   how to list resources from the default elasticsearch views
  * @param fetchResource
  *   how to fetch a resource given a resourceRef and the project it lives in
  */
class IdResolution(
    defaultViewsQuery: DefaultViewsQuery.Elasticsearch,
    fetchResource: (ResourceRef, ProjectRef) => UIO[Option[JsonLdContent[_, _]]]
) {

  /**
    * Attempts to resolve the provided identifier across projects that the caller has access to
    *
    *   - If the query response is empty, leads to AuthorizationFailed.
    *   - If the query returns a single result, attempts to fetch the resource
    *   - If there are multiple results, they are returned as [[SearchResults]]
    *
    * @param iri
    *   identifier of the resource to be resolved
    * @param caller
    *   user having requested the resolution
    */
  def resolve(
      iri: Iri
  )(implicit caller: Caller): IO[ElasticSearchQueryError, Result] = {
    val locate  = ResourcesSearchParams(id = Some(iri))
    val request = RootSearch(locate, FromPagination(0, 10000), SortList.empty)

    def fetchSingleResult: ProjectRef => UIO[Result] = { projectRef =>
      val resourceRef = ResourceRef(iri)
      fetchResource(resourceRef, projectRef)
        .map {
          _.map(SingleResult(resourceRef, projectRef, _))
        }
        .flatMap {
          case Some(result) => IO.pure(result)
          case None         => IO.terminate(new UnexpectedException("Resource found in ES payload but could not be fetched."))
        }
    }

    defaultViewsQuery
      .list(request)
      .flatMap { searchResults =>
        searchResults.results match {
          case Nil         => IO.raiseError(AuthorizationFailed)
          case Seq(result) => projectRefFromSource(result.source).flatMap(fetchSingleResult)
          case _           => UIO.pure(MultipleResults(searchResults))
        }
      }
  }

  /** Extract the _project field of a given [[JsonObject]] as projectRef */
  private def projectRefFromSource(source: JsonObject) =
    source("_project")
      .flatMap(_.as[Iri].toOption)
      .flatMap(projectRefFromIri) match {
      case Some(projectRef) => UIO.pure(projectRef)
      case None             => UIO.terminate(new UnexpectedException("Could not read '_project' field as IRI."))
    }

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

  final case class SingleResult[A](id: ResourceRef, project: ProjectRef, content: JsonLdContent[A, _]) extends Result

  case class MultipleResults(searchResults: SearchResults[JsonObject]) extends Result

  private val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.search))

  implicit def resultJsonLdEncoder: JsonLdEncoder[Result] =
    new JsonLdEncoder[Result] {

      // helps with type inference
      private def encoder[A](value: JsonLdContent[A, _]): JsonLdEncoder[A] = value.encoder

      override def context(value: Result): ContextValue = value match {
        case SingleResult(_, _, content)    => encoder(content).context(content.resource.value)
        case MultipleResults(searchResults) => searchJsonLdEncoder.context(searchResults)
      }

      override def expand(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        value match {
          case SingleResult(_, _, content)    => encoder(content).expand(content.resource.value)
          case MultipleResults(searchResults) => searchJsonLdEncoder.expand(searchResults)
        }

      override def compact(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        value match {
          case SingleResult(_, _, content)    => encoder(content).compact(content.resource.value)
          case MultipleResults(searchResults) => searchJsonLdEncoder.compact(searchResults)
        }
    }

}
