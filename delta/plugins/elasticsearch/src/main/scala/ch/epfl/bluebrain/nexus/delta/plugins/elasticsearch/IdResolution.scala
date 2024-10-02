package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.RootSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultViewsQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.JsonObject

import java.rmi.UnexpectedException

/**
  * @param defaultViewsQuery
  *   how to list resources from the default elasticsearch views
  * @param fetchResource
  *   how to fetch a resource given a resourceRef and the project it lives in
  */
class IdResolution(
    defaultViewsQuery: DefaultViewsQuery.Elasticsearch,
    fetchResource: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[_, _]]]
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
  )(implicit caller: Caller): IO[Result] = {
    val locate  = ResourcesSearchParams(id = Some(iri))
    val request = RootSearch(locate, FromPagination(0, 10000), SortList.empty)

    def fetchSingleResult: ProjectRef => IO[Result] = { projectRef =>
      val resourceRef = ResourceRef(iri)
      fetchResource(resourceRef, projectRef)
        .map {
          _.map(SingleResult(resourceRef, projectRef, _))
        }
        .flatMap {
          case Some(result) => IO.pure(result)
          case None         => IO.raiseError(new UnexpectedException("Resource found in ES payload but could not be fetched."))
        }
    }

    defaultViewsQuery
      .list(request)
      .flatMap { searchResults =>
        searchResults.results match {
          case Nil         => IO.raiseError(AuthorizationFailed("No resource matches the provided id."))
          case Seq(result) => projectRefFromSource(result.source).flatMap(fetchSingleResult)
          case _           => IO.pure(MultipleResults(searchResults))
        }
      }
  }

  /** Extract the _project field of a given [[JsonObject]] as projectRef */
  private def projectRefFromSource(source: JsonObject) =
    source("_project")
      .flatMap(_.as[Iri].toOption)
      .flatMap(projectRefFromIri) match {
      case Some(projectRef) => IO.pure(projectRef)
      case None             => IO.raiseError(new UnexpectedException("Could not read '_project' field as IRI."))
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

  implicit def resultJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[Result] =
    new JsonLdEncoder[Result] {

      private def encoder[A](value: JsonLdContent[A, _])(implicit baseUri: BaseUri): JsonLdEncoder[ResourceF[A]] = {
        implicit val encoder: JsonLdEncoder[A] = value.encoder
        resourceFAJsonLdEncoder[A](ContextValue.empty)
      }

      override def context(value: Result): ContextValue = value match {
        case SingleResult(_, _, content)    => encoder(content).context(content.resource)
        case MultipleResults(searchResults) => searchJsonLdEncoder.context(searchResults)
      }

      override def expand(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
        value match {
          case SingleResult(_, _, content)    => encoder(content).expand(content.resource)
          case MultipleResults(searchResults) => searchJsonLdEncoder.expand(searchResults)
        }

      override def compact(
          value: Result
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
        value match {
          case SingleResult(_, _, content)    => encoder(content).compact(content.resource)
          case MultipleResults(searchResults) => searchJsonLdEncoder.compact(searchResults)
        }
    }

  implicit val reultHttpResponseFields: HttpResponseFields[Result] = HttpResponseFields.defaultOk

}
