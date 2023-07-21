package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.InvalidResourceId
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import monix.bio.IO

/**
  * Search request on default elasticsearch views
  */
sealed trait DefaultSearchRequest extends Product with Serializable {

  /**
    * Filter to apply
    */
  def params: ResourcesSearchParams

  /**
    * Pagination to apply
    */
  def pagination: Pagination

  /**
    * Sort to apply
    */
  def sort: SortList

  /**
    * If the search applies to the project/org/root level
    */
  def predicate: Predicate

}

object DefaultSearchRequest {

  /**
    * Search to be performed on a project
    */
  case class ProjectSearch(ref: ProjectRef, params: ResourcesSearchParams, pagination: Pagination, sort: SortList)
      extends DefaultSearchRequest {
    override def predicate: Predicate = Predicate.Project(ref)
  }

  object ProjectSearch {
    def apply(
        ref: ProjectRef,
        params: ResourcesSearchParams,
        pagination: Pagination,
        sort: SortList,
        schema: IdSegment
    )(fetchContext: FetchContext[ElasticSearchQueryError]): IO[ElasticSearchQueryError, ProjectSearch] =
      fetchContext
        .onRead(ref)
        .flatMap { context =>
          expandResourceRef(schema, context.apiMappings, context.base)
        }
        .map { schemaRef =>
          ProjectSearch(ref, params.withSchema(schemaRef), pagination, sort: SortList)
        }

    /** An apply method that uses default values for pagination and sorting */
    def apply(ref: ProjectRef, params: ResourcesSearchParams): ProjectSearch =
      ProjectSearch(ref, params, Pagination.OnePage, SortList.empty)

    /** An apply method that uses default values for pagination and sorting */
    def apply(ref: ProjectRef, params: ResourcesSearchParams, schema: IdSegment)(
        fetchContext: FetchContext[ElasticSearchQueryError]
    ): IO[ElasticSearchQueryError, ProjectSearch] =
      apply(ref, params, Pagination.OnePage, SortList.empty, schema)(fetchContext)
  }

  /**
    * Search to be performed on an org
    */
  case class OrgSearch(label: Label, params: ResourcesSearchParams, pagination: Pagination, sort: SortList)
      extends DefaultSearchRequest {
    override def predicate: Predicate = Predicate.Org(label)
  }

  object OrgSearch {
    def apply(label: Label, params: ResourcesSearchParams, pagination: Pagination, sort: SortList, schema: IdSegment)(
        fetchContext: FetchContext[ElasticSearchQueryError]
    ): IO[ElasticSearchQueryError, OrgSearch] =
      expandResourceRef(schema, fetchContext).map { resourceRef =>
        OrgSearch(label, params.withSchema(resourceRef), pagination, sort)
      }

    /** An apply method that uses default values for pagination and sorting */
    def apply(label: Label, params: ResourcesSearchParams): OrgSearch =
      OrgSearch(label, params, Pagination.OnePage, SortList.empty)
  }

  /**
    * Search to be performed on all default views
    */
  case class RootSearch(params: ResourcesSearchParams, pagination: Pagination, sort: SortList)
      extends DefaultSearchRequest {
    override def predicate: Predicate = Predicate.Root
  }

  object RootSearch {
    def apply(params: ResourcesSearchParams, pagination: Pagination, sort: SortList, schema: IdSegment)(
        fetchContext: FetchContext[ElasticSearchQueryError]
    ): IO[ElasticSearchQueryError, RootSearch] =
      expandResourceRef(schema, fetchContext).map { resourceRef =>
        RootSearch(params.withSchema(resourceRef), pagination, sort)
      }

    /** An apply method that uses default values for pagination and sorting */
    def apply(params: ResourcesSearchParams): RootSearch =
      RootSearch(params, Pagination.OnePage, SortList.empty)
  }

  private def expandResourceRef(
      segment: IdSegment,
      fetchContext: FetchContext[ElasticSearchQueryError]
  ): IO[InvalidResourceId, ResourceRef] =
    expandResourceRef(segment, fetchContext.defaultApiMappings, ProjectBase(iri""))

  private def expandResourceRef(
      segment: IdSegment,
      mappings: ApiMappings,
      base: ProjectBase
  ): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(mappings, base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

}
