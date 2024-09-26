package ch.epfl.bluebrain.nexus.delta.sourcing.query

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, IriFilter, Tag}

/**
  * Contains the information that can be used for filtering when streaming states
  * @param types
  *   types that a view is indexing
  * @param tag
  *   tag that a view is indexing
  */
case class SelectFilter(entityType: Option[EntityType], types: IriFilter, tag: Tag)

object SelectFilter {

  /** All types with specified tag */
  val tag: Tag => SelectFilter = SelectFilter(None, IriFilter.None, _)

  /** All types with latest tag */
  val latest: SelectFilter = SelectFilter(None, IriFilter.None, Tag.Latest)

  /**
    * All of given entity with latest tag
    */
  val latestOfEntity: EntityType => SelectFilter =
    entityType => SelectFilter(Some(entityType), IriFilter.None, Tag.Latest)

}
