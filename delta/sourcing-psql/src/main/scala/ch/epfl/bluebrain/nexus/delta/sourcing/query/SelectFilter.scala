package ch.epfl.bluebrain.nexus.delta.sourcing.query

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Tag, ViewRestriction}

/**
  * Contains the information that can be used for filtering when streaming states
  * @param types
  *   types that a view is indexing
  * @param tag
  *   tag that a view is indexing
  */
case class SelectFilter(types: ViewRestriction, tag: Tag)

object SelectFilter {

  /** All types with specified tag */
  val tag: Tag => SelectFilter = SelectFilter(ViewRestriction.None, _)

  /** All types with latest tag */
  val latest: SelectFilter = SelectFilter(ViewRestriction.None, Tag.Latest)

}
