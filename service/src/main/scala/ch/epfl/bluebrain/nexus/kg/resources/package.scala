package ch.epfl.bluebrain.nexus.kg

import akka.http.scaladsl.model.StatusCodes.BadRequest
import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, UnexpectedUnsuccessfulHttpResponse}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination, QueryResults}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.kg.KgError.InternalError
import ch.epfl.bluebrain.nexus.kg.archives.Archive
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResource
import ch.epfl.bluebrain.nexus.kg.resources.file.File.FileAttributes
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.kg.search.QueryBuilder.queryFor
import ch.epfl.bluebrain.nexus.kg.storage.{AkkaSource, Storage}
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import io.circe.Json

package object resources {

  /**
    * A resource id rooted in a project reference.
    */
  type ResId = Id[ProjectRef]

  /**
    * Primary resource representation.
    */
  type Resource = ResourceF[Json]

  /**
    * Resource representation with a "source", "flattened" context and "computed" graph.
    */
  type ResourceV = ResourceF[ResourceF.Value]

  /**
    * Resource representation with a "computed" graph.
    */
  type ResourceGraph = ResourceF[Graph]

  /**
    * Resource tags
    */
  type TagSet = Set[Tag]

  /**
    * Rejection or resource representation with a "source", "flattened" context and "computed" graph wrapped in F[_]
    */
  type RejOrResourceV[F[_]] = EitherT[F, Rejection, ResourceV]

  /**
    * Rejection or bytestring source wrapped in F[_]
    */
  type RejOrAkkaSource[F[_]] = EitherT[F, Rejection, AkkaSource]

  /**
    * Rejection or schema reference wrapped in F[_]
    */
  type RejOrSchema[F[_]] = EitherT[F, Rejection, Ref]

  /**
    * Rejection or json wrapped in F[_]
    */
  type RejOrSource[F[_]] = EitherT[F, Rejection, Json]

  /**
    * Rejection or resource representation with a "source" wrapped in F[_]
    */
  type RejOrResource[F[_]] = EitherT[F, Rejection, Resource]

  /**
    * Rejection or archive wrapped in F[_]
    */
  type RejOrArchive[F[_]] = EitherT[F, Rejection, Archive]

  /**
    * Rejection or tags representation wrapped in F[_]
    */
  type RejOrTags[F[_]] = EitherT[F, Rejection, TagSet]

  /**
    * Rejection or Unit wrapped in F[_]
    */
  type RejOrUnit[F[_]] = EitherT[F, Rejection, Unit]

  /**
    * Rejection or file representation containing the storage, the file attributes and the Source wrapped in F[_]
    */
  type RejOrFile[F[_], Out] = EitherT[F, Rejection, (Storage, FileAttributes, Out)]

  /**
    * Query results of type Json
    */
  type JsonResults = QueryResults[Json]

  /**
    * Query results of type [[SparqlLink]]
    */
  type LinkResults = QueryResults[SparqlLink]

  /**
    * Rejection or query results of type [[SparqlLink]]
    */
  type RejOrLinkResults = Either[Rejection, LinkResults]

  /**
    * Rejection or project [[ProjectResource]]
    */
  type RejOrProject = Either[Rejection, ProjectResource]

  private val sortErr = ".*No mapping found for \\[\\w*\\] in order to sort.*"

  private[resources] def listResources[F[_]](
      view: Option[ElasticSearchView],
      params: SearchParams,
      pagination: Pagination
  )(implicit
      F: Effect[F],
      config: AppConfig,
      tc: HttpClient[F, JsonResults],
      elasticSearch: ElasticSearchClient[F]
  ): F[JsonResults] =
    view
      .map { v =>
        elasticSearch.search[Json](queryFor(params), Set(v.index))(pagination, sort = params.sort).recoverWith {
          case UnexpectedUnsuccessfulHttpResponse(resp, body) if resp.status == BadRequest && body.matches(sortErr) =>
            F.raiseError(ElasticSearchClientError(BadRequest, body))
          case other                                                                                                =>
            F.raiseError(other)
        }
      }
      .getOrElse(F.pure[JsonResults](UnscoredQueryResults(0L, List.empty)))

  private[resources] def incoming[F[_]](
      id: AbsoluteIri,
      view: Option[SparqlView],
      pagination: FromPagination
  )(implicit F: Effect[F], config: AppConfig, client: BlazegraphClient[F]): F[LinkResults] =
    view.map(_.incoming(id, pagination)).getOrElse(F.pure[LinkResults](UnscoredQueryResults(0L, List.empty)))

  private[resources] def outgoing[F[_]](
      id: AbsoluteIri,
      view: Option[SparqlView],
      pagination: FromPagination,
      includeExternalLinks: Boolean
  )(implicit F: Effect[F], config: AppConfig, client: BlazegraphClient[F]): F[LinkResults] = {
    view
      .map(_.outgoing(id, pagination, includeExternalLinks))
      .getOrElse(F.pure[LinkResults](UnscoredQueryResults(0L, List.empty)))
  }

  private[resources] def toEitherT[F[_]](
      schema: Ref,
      report: Either[String, ValidationReport]
  )(implicit F: Effect[F]): EitherT[F, Rejection, Unit] =
    report match {
      case Right(r) if r.isValid() => EitherT.rightT(())
      case Right(r)                => EitherT.leftT(InvalidResource(schema, r))
      case Left(err)               =>
        val msg = s"Unexpected error while attempting to validate schema '${schema.iri.asString}'' with error '$err'"
        EitherT(F.raiseError(InternalError(msg): KgError))
    }

}
