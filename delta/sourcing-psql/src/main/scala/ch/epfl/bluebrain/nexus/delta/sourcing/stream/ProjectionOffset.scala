package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset

/**
  * The collection of projection offsets indexed by their context.
  *
  * @param toMap
  *   the map of [[ElemCtx]] -> [[Offset]] pairs
  */
final case class ProjectionOffset(toMap: Map[ElemCtx, Offset]) {

  /**
    * Identifies the offset for the provided [[Source]] yielding [[Offset.Start]] if none is found.
    * @param source
    *   the source for which the offset is determined
    */
  def forSource(source: Source): Offset =
    toMap
      .foldLeft(Set.empty[Offset]) {
        case (acc, (ElemCtx.SourceId(sourceId), offset)) if sourceId == source.id               => acc + offset
        case (acc, (ElemCtx.SourceIdPipeChainId(sourceId, _), offset)) if sourceId == source.id => acc + offset
        case (acc, _)                                                                           => acc
      }
      .minOption
      .getOrElse(Offset.Start)
}

object ProjectionOffset {

  /**
    * The empty offset.
    */
  final val empty: ProjectionOffset = ProjectionOffset(Map.empty)

  /**
    * Constructs an offset from the provided `ctx` -> `offset` values.
    * @param ctx
    *   the offset context
    * @param offset
    *   the offset
    */
  def apply(ctx: ElemCtx, offset: Offset): ProjectionOffset     =
    ProjectionOffset(Map(ctx -> offset))

  implicit val projectionOffsetMonoid: Monoid[ProjectionOffset] =
    Monoid.instance(empty, { (left, right) => ProjectionOffset(left.toMap ++ right.toMap) })
}
