package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{nxvStorage, schemas, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.UIO

/**
  * Search parameters for storage.
  *
  * @param project
  *   the optional parent project of the storages
  * @param deprecated
  *   the optional deprecation status of the storages
  * @param rev
  *   the optional revision of the storages
  * @param createdBy
  *   the optional subject who created the storages
  * @param updatedBy
  *   the optional subject who last updated the storages
  * @param types
  *   the types the storage should contain
  * @param filter
  *   an additional resource filter
  */
final case class StorageSearchParams(
    project: Option[ProjectRef] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Int] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: Set[Iri] = Set(nxvStorage),
    filter: Storage => UIO[Boolean]
) extends SearchParams[Storage] {

  override val schema: Option[ResourceRef] = Some(Latest(schemas.storage))

  override def matches(resource: StorageResource): UIO[Boolean] =
    super.matches(resource).map(_ && project.forall(_ == resource.value.project))

}
