package ch.epfl.bluebrain.nexus.delta.sourcing.query

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Tag, ValidViewTypes}

/**
  * Contains the information that can be used for filtering when streaming states
  * @param types
  *   types that a view is indexing
  * @param tag
  *   tag that a view is indexing
  */
case class SelectFilter(types: ValidViewTypes, tag: Tag)

object SelectFilter {

  /** All types with specified tag */
  val tag: Tag => SelectFilter = SelectFilter(ValidViewTypes.All, _)

  /** All types with latest tag */
  val latest: SelectFilter = SelectFilter(ValidViewTypes.All, Tag.Latest)

}
