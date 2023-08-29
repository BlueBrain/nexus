package ch.epfl.bluebrain.nexus.delta.sourcing.query

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag
import doobie.util.fragment.Fragment

/**
  * Contains the information that can be used for filtering when streaming states
  * @param types
  *   types that a view is indexing
  * @param tag
  *   tag that a view is indexing
  */
case class SelectFilter(types: Set[Iri], tag: Tag) {

  /** The types as a postgres array */
  def typeSqlArray: Fragment =
    Fragment.const(s"ARRAY[${types.map(t => s"'$t'").mkString(",")}]")

}

object SelectFilter {

  /** All types with specified tag */
  val tag: Tag => SelectFilter = SelectFilter(Set.empty, _)

  /** All types with latest tag */
  val latest: SelectFilter = SelectFilter(Set.empty, Tag.Latest)

  /** All types with specified tag if it exists, otherwise latest */
  val tagOrLatest: Option[Tag] => SelectFilter =
    tag => SelectFilter(Set.empty, tag.getOrElse(Tag.Latest))

}
