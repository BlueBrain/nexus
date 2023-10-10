package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, Directive0, Directive1, MalformedQueryParamRejection}
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.TypeOperator.Or
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.{Type, TypeOperator}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{DeltaSchemeDirectives, UriDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.IriBase
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef

trait ElasticSearchViewsDirectives extends UriDirectives {

  implicit private val sortFromStringUnmarshaller: FromStringUnmarshaller[Sort] =
    Unmarshaller.strict[String, Sort](Sort(_))

  private val simultaneousSortAndQRejection =
    MalformedQueryParamRejection("sort", "'sort' and 'q' parameters cannot be present simultaneously.")

  private val searchParamsSortAndPaginationKeys =
    Set("deprecated", "id", "rev", "from", "size", "after", "type", "schema", "createdBy", "updatedBy", "sort", "q")

  private def types(implicit um: FromStringUnmarshaller[Type]): Directive1[List[Type]] =
    parameter("type".as[Type].*).map(_.toList.reverse)

  private def typeOperator(implicit um: FromStringUnmarshaller[TypeOperator]): Directive1[TypeOperator] = {
    parameter("typeOperator".as[TypeOperator].?[TypeOperator](Or))
  }

  private def schema(implicit um: FromStringUnmarshaller[IriBase]): Directive1[Option[ResourceRef]] =
    parameter("schema".as[IriBase].?).map(_.map(iri => ResourceRef(iri.value)))

  private def locate(implicit um: FromStringUnmarshaller[IriBase]): Directive1[Option[Iri]] =
    parameter("locate".as[IriBase].?).map(_.map(_.value))

  private def id(implicit um: FromStringUnmarshaller[IriBase]): Directive1[Option[Iri]] =
    parameter("id".as[IriBase].?).map(_.map(_.value))

  /**
    * Matches only if the ''aggregations'' parameter is set to ''true''
    */
  def aggregated: Directive0 =
    parameter("aggregations".as[Boolean].requiredValue(true))

  /**
    * Extract the ''sort'' query parameter(s) and provide a [[SortList]]
    */
  def sortList: Directive1[SortList] =
    parameter("sort".as[Sort].*).map {
      case s if s.isEmpty => SortList.empty
      case s              => SortList(s.toList.reverse)
    }

  /**
    * Extract the query parameters related to search: ''deprecated'', ''rev'', ''createdBy'', ''updatedBy'', ''type'',
    * ''schema'', ''id'', ''q'' and converts each of them to the appropriate type
    */
  private[routes] def searchParameters(implicit
      baseUri: BaseUri,
      pc: ProjectContext
  ): Directive1[ResourcesSearchParams] = {
    (searchParams & createdAt & updatedAt & types & typeOperator & schema & id & locate & parameter("q".?)).tmap {
      case (deprecated, rev, createdBy, updatedBy, createdAt, updatedAt, types, typeOperator, schema, id, locate, q) =>
        val qq = q.filter(_.trim.nonEmpty).map(_.toLowerCase)
        ResourcesSearchParams(
          locate,
          id,
          deprecated,
          rev,
          createdBy,
          createdAt,
          updatedBy,
          updatedAt,
          types,
          typeOperator,
          schema,
          qq
        )
    }
  }

  private[routes] def searchParameters(implicit
      baseUri: BaseUri
  ): Directive1[ResourcesSearchParams] = {
    implicit val typesUm: FromStringUnmarshaller[Type]      = Type.typeFromStringUnmarshallerNoExpansion
    implicit val baseIriUm: FromStringUnmarshaller[IriBase] =
      DeltaSchemeDirectives.iriBaseFromStringUnmarshallerNoExpansion

    (searchParams & createdAt & updatedAt & types & typeOperator & schema & id & locate & parameter("q".?)).tmap {
      case (deprecated, rev, createdBy, updatedBy, createdAt, updatedAt, types, typeOperator, schema, id, locate, q) =>
        val qq = q.filter(_.trim.nonEmpty).map(_.toLowerCase)
        ResourcesSearchParams(
          locate,
          id,
          deprecated,
          rev,
          createdBy,
          createdAt,
          updatedBy,
          updatedAt,
          types,
          typeOperator,
          schema,
          qq
        )
    }
  }

  private[routes] def searchParametersAndSortList(implicit
      baseUri: BaseUri
  ): Directive[(ResourcesSearchParams, SortList)] =
    (searchParameters(baseUri) & sortList).tflatMap { case (params, sortList) =>
      if (params.q.isDefined && !sortList.isEmpty) reject(simultaneousSortAndQRejection)
      else if (params.q.isEmpty && sortList.isEmpty) tprovide((params, SortList.byCreationDateAndId))
      else tprovide((params, sortList))
    }

  /**
    * Extracts the query parameters for [[ResourcesSearchParams]] and [[SortList]]. Rejects if both the ''q'' and
    * ''sort'' query params are present, since they are incompatible.
    */
  def searchParametersInProject(implicit
      baseUri: BaseUri,
      pc: ProjectContext
  ): Directive[(ResourcesSearchParams, SortList)] =
    (searchParameters(baseUri, pc) & sortList).tflatMap { case (params, sortList) =>
      if (params.q.isDefined && !sortList.isEmpty) reject(simultaneousSortAndQRejection)
      else if (params.q.isEmpty && sortList.isEmpty) tprovide((params, SortList.byCreationDateAndId))
      else tprovide((params, sortList))
    }

  /**
    * Extract the elasticsearch query parameters from all the [[Uri]] query parameters
    */
  def extractQueryParams: Directive1[Uri.Query] =
    extractUri.map { uri =>
      Uri.Query(uri.query().toMap -- searchParamsSortAndPaginationKeys)
    }

}

object ElasticSearchViewsDirectives extends ElasticSearchViewsDirectives
