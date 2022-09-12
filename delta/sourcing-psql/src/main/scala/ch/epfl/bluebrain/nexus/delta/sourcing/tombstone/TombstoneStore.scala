package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ResourceRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Json}

import scala.annotation.nowarn

object TombstoneStore {

  /**
    * Saves a tombstone for the given entity for the provided tag so that indexing processes can take it into account
    */
  def save[Id, S <: ScopedState](tpe: EntityType, id: Id, state: S, removedTag: Tag)(implicit
      putId: Put[Id]
  ): ConnectionIO[Unit] =
    sql"""
         | INSERT INTO public.scoped_tombstones (
         |  type,
         |  org,
         |  project,
         |  id,
         |  tag,
         |  diff,
         |  instant
         | )
         | VALUES (
         |  $tpe,
         |  ${state.organization},
         |  ${state.project.project},
         |  $id,
         |  $removedTag,
         |  ${Json.obj()},
         |  ${state.updatedAt}
         | )""".stripMargin.update.run.void

  /**
    * Saves a tombstone when an entity is updated and some of its types have been removed and/or it has been validated
    * against a new schema
    */
  def save[Id, S <: ScopedState](tpe: EntityType, id: Id, original: Option[S], newState: S)(implicit
      putId: Put[Id]
  ): ConnectionIO[Unit] =
    StateDiff.compute(original, newState).fold(().pure[ConnectionIO]) { diff =>
      sql"""
           | INSERT INTO public.scoped_tombstones (
           |  type,
           |  org,
           |  project,
           |  id,
           |  tag,
           |  diff,
           |  instant
           | )
           | VALUES (
           |  $tpe,
           |  ${newState.organization},
           |  ${newState.project.project},
           |  $id,
           |  ${Tag.latest},
           |  ${diff.asJson},
           |  ${newState.updatedAt}
           | )""".stripMargin.update.run.void
    }

  final private[tombstone] case class StateDiff(types: Set[Iri], schema: Option[ResourceRef])

  object StateDiff {

    implicit val stateDiffEncoder: Codec[StateDiff] = {
      @nowarn("cat=unused")
      implicit val configuration: Configuration = Configuration.default
      deriveConfiguredCodec[StateDiff]
    }

    def compute[S <: ScopedState](original: Option[S], newState: S): Option[StateDiff] =
      original.flatMap { o =>
        val removedTypes   = o.types.diff(newState.types)
        val modifiedSchema = Option.when(o.schema != newState.schema)(o.schema)
        Option.when(removedTypes.nonEmpty || modifiedSchema.isDefined)(
          StateDiff(removedTypes, modifiedSchema)
        )
      }

  }

}
