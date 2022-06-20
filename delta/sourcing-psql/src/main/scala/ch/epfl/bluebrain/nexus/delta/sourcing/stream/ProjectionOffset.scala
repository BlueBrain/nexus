package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.{Get, Put}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Codec, Decoder, Encoder}

import scala.annotation.nowarn

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

  /**
    * Adds the ElemCtx->Offset to the ProjectionOffset.
    * @param ctx
    *   the ctx key
    * @param offset
    *   the offset value
    */
  def add(ctx: ElemCtx, offset: Offset): ProjectionOffset =
    this |+| ProjectionOffset(ctx, offset)
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

  private[stream] case class ProjectionOffsetEntry(ctx: ElemCtx, offset: Offset)
  object ProjectionOffsetEntry {
    implicit final val projectionOffsetEntryCodec: Codec[ProjectionOffsetEntry] = {
      @nowarn("cat=unused")
      implicit val configuration: Configuration =
        Configuration.default.withDiscriminator(keywords.tpe)
      deriveConfiguredCodec[ProjectionOffsetEntry]
    }
  }

  implicit final val projectionOffsetCodec: Codec[ProjectionOffset] =
    Codec.from(
      Decoder.decodeList[ProjectionOffsetEntry].map(es => ProjectionOffset(es.map(e => (e.ctx, e.offset)).toMap)),
      Encoder
        .encodeList[ProjectionOffsetEntry]
        .contramap[ProjectionOffset](po =>
          po.toMap.toList.map({ case (ctx, offset) => ProjectionOffsetEntry(ctx, offset) })
        )
    )

  implicit val projectionOffsetGet: Get[ProjectionOffset] =
    jsonbGet.temap(json => projectionOffsetCodec.apply(json.hcursor).leftMap(_.getMessage()))

  implicit val projectionOffsetPut: Put[ProjectionOffset] =
    jsonbPut.contramap(o => projectionOffsetCodec.apply(o))
}
