package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, Directive1, MalformedQueryParamRejection}
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.{IriBase, IriVocab}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import monix.execution.Scheduler

trait ElasticSearchViewsDirectives extends UriDirectives {

  implicit private val sortFromStringUnmarshaller: FromStringUnmarshaller[Sort] =
    Unmarshaller.strict[String, Sort](Sort(_))

  private val simultaneousSortAndQRejection =
    MalformedQueryParamRejection("sort", "'sort' and 'q' parameters cannot be present simultaneously.")

  private val searchParamsSortAndPaginationKeys =
    Set("deprecated", "id", "rev", "from", "size", "after", "type", "schema", "createdBy", "updatedBy", "sort", "q")

  private def typesSchemaAndId(implicit
      projectRef: ProjectRef,
      fetchProject: FetchProject,
      sc: Scheduler
  ): Directive[(List[Iri], Option[ResourceRef], Option[Iri])] =
    onSuccess(fetchProject(projectRef).attempt.runToFuture).flatMap {
      case Right(projectResource) =>
        implicit val project: Project = projectResource.value
        (parameter("type".as[IriVocab].*) & parameter("schema".as[IriBase].?) & parameter("id".as[IriBase].?)).tmap {
          case (types, schema, id) =>
            (types.toList.reverse.map(_.value), schema.map(iri => ResourceRef(iri.value)), id.map(_.value))
        }
      case _                      =>
        tprovide((List.empty[Iri], None, None))
    }

  /**
    * Extract the ''sort'' query parameter(s) and provide a [[SortList]]
    */
  private[routes] def sortList: Directive1[SortList] =
    parameter("sort".as[Sort].*).map {
      case s if s.isEmpty => SortList.empty
      case s              => SortList(s.toList.reverse)
    }

  /**
    * Extract the query parameters related to search: ''deprecated'', ''rev'', ''createdBy'', ''updatedBy'', ''type'', ''schema'', ''id'', ''q''
    * and converts each of them to the appropriate type
    */
  private[routes] def searchParameters(implicit
      projectRef: ProjectRef,
      baseUri: BaseUri,
      fetchProject: FetchProject,
      sc: Scheduler
  ): Directive1[ResourcesSearchParams] =
    (searchParams & typesSchemaAndId & parameter("q".?)).tmap {
      case (deprecated, rev, createdBy, updatedBy, types, schema, id, q) =>
        ResourcesSearchParams(id, deprecated, rev, createdBy, updatedBy, types, schema, q.filter(_.trim.nonEmpty))
    }

  /**
    * Extracts the query parameters for [[ResourcesSearchParams]] and [[SortList]].
    * Rejects if both the ''q'' and ''sort'' query params are present, since they are incompatible.
    */
  def searchParametersAndSortList(implicit
      projectRef: ProjectRef,
      baseUri: BaseUri,
      fetchProject: FetchProject,
      sc: Scheduler
  ): Directive[(ResourcesSearchParams, SortList)] =
    (searchParameters & sortList).tflatMap { case (params, sortList) =>
      if (params.q.isDefined && !sortList.isEmpty)
        reject(simultaneousSortAndQRejection)
      else if (params.q.isEmpty && sortList.isEmpty)
        tprovide((params, SortList.byCreationDateAndId))
      else
        tprovide((params, sortList))
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
