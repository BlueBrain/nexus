package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams

/**
  * Search parameters for ElasticSearch views.
  *
  * @param project    the optional parent project of the views
  * @param deprecated the optional deprecation status of the views
  * @param rev        the optional revision of the views
  * @param createdBy  the optional subject who created the views
  * @param updatedBy  the optional subject who last updated the views
  * @param types      the collection of types to consider, where empty implies all views
  * @param filter     an additional resource filter
  */
final case class ElasticSearchViewSearchParams(
    project: Option[ProjectRef] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: Set[Iri] = Set(ElasticSearch, AggregateElasticSearch).map(_.iri),
    filter: ElasticSearchView => Boolean
) extends SearchParams[ElasticSearchView] {

  override val schema: Option[ResourceRef] = Some(model.schema)

  override def matches(resource: ElasticSearchViewResource): Boolean =
    super.matches(resource) &&
      project.forall(_ == resource.value.project)
}
