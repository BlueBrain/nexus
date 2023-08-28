package ch.epfl.bluebrain.nexus.delta.sourcing.query

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag

/**
  * Contains the information that
  * @param types
  *   types that a view is indexing
  * @param tag
  *   tag that a view is indexing
  */
case class SelectFilter(types: Set[Iri], tag: Tag)

object SelectFilter {

  /** All types with specified tag */
  val tag: Tag => SelectFilter = SelectFilter(Set.empty, _)

  /** All types with latest tag */
  val latest: SelectFilter = SelectFilter(Set.empty, Tag.Latest)

  /** All types with specified tag if it exists, otherwise latest */
  val tagOrLatest: Option[Tag] => SelectFilter = tag => SelectFilter(Set.empty, tag.getOrElse(Tag.Latest))

}
