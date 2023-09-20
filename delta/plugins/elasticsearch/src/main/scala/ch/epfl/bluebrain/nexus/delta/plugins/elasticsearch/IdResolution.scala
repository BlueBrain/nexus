package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.RootSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultViewsQuery
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
import io.circe.{Encoder, Json, JsonObject}
import monix.bio.{IO, UIO}

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
  )(implicit caller: Caller): UIO[Result] = {
    val locate  = ResourcesSearchParams(id = Some(iri))
    val request = RootSearch(locate, Pagination.OnePage, SortList.empty)

    defaultViewsQuery
      .list(request)
      .flatMap { searchResults =>
        searchResults.results match {
          case Nil         => UIO.pure(AuthorizationFailed(ResourceRef(iri)))
          case Seq(result) =>
            projectRefFromSource(result.source)
              .traverse { projectRef =>
                val resourceRef = ResourceRef(iri)
                fetchResource(resourceRef, projectRef).map {
                  _.map(SingleResult(resourceRef, projectRef, _))
                    .getOrElse(UnexpectedError)
                }
              }
              .map(_.getOrElse(UnexpectedError))
          case _           => UIO.pure(MultipleResults(searchResults))
        }
      }
      .onErrorHandle(e => ElasticSearchQueryError(e.reason))
  }

  private def projectRefFromSource(source: JsonObject) =
    source("_project")
      .flatMap(_.as[Iri].toOption)
      .flatMap(projectRefFromIri)

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

  sealed trait Error extends Result {
    def reason: String
  }

  final case class AuthorizationFailed(id: ResourceRef) extends Error {
    override def reason: String = "The supplied authentication is not authorized to access this resource."
  }

  final case class ElasticSearchQueryError(reason: String) extends Error

  final case object UnexpectedError extends Error {
    override def reason: String = s"An unexpected error occurred"
  }

  final case class SingleResult[A](id: ResourceRef, project: ProjectRef, content: JsonLdContent[A, _]) extends Result

  case class MultipleResults(searchResults: SearchResults[JsonObject]) extends Result

  implicit private val errorEncoder: Encoder.AsObject[Error] =
    Encoder.AsObject.instance[Error] { r =>
      JsonObject(
        "@type"  -> Json.fromString(r.getClass.getSimpleName),
        "reason" -> Json.fromString(r.reason)
      )
    }

  private val errorJsonLdEncoder: JsonLdEncoder[Error] = {
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
  }

  private val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.metadata))

  implicit def resultJsonLdEncoder: JsonLdEncoder[Result] =
    new JsonLdEncoder[Result] {

      override def context(value: Result): ContextValue = value match {
        case error: Error                   => errorJsonLdEncoder.context(error)
        case SingleResult(_, _, content)    => content.encoder.context(content)
        case MultipleResults(searchResults) => searchJsonLdEncoder.context(searchResults)
      }

      override def expand(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        value match {
          case error: Error                   => errorJsonLdEncoder.expand(error)
          case SingleResult(_, _, content)    => content.encoder.expand(content)
          case MultipleResults(searchResults) => searchJsonLdEncoder.expand(searchResults)
        }

      override def compact(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        value match {
          case error: Error                   => errorJsonLdEncoder.compact(error)
          case SingleResult(_, _, content)    => content.encoder.compact(content)
          case MultipleResults(searchResults) => searchJsonLdEncoder.compact(searchResults)
        }
    }

}
