package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.{schemas, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams

/**
  * Search parameters for storage.
  *
  * @param project    the optional parent project of the storages
  * @param deprecated the optional deprecation status of the storages
  * @param rev        the optional revision of the storages
  * @param createdBy  the optional subject who created the storages
  * @param updatedBy  the optional subject who last updated the storages
  * @param types      the collection of types to consider, where empty implies all storages
  * @param filter     an additional resource filter
  */
final case class StorageSearchParams(
    project: Option[ProjectRef] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: Set[Iri] = Set.empty[Iri],
    filter: Storage => Boolean
) extends SearchParams[Storage] {

  override val schema: Option[ResourceRef] = Some(Latest(schemas.storage))

  override def matches(resource: StorageResource): Boolean =
    super.matches(resource) &&
      project.forall(_ == resource.value.project)
}
