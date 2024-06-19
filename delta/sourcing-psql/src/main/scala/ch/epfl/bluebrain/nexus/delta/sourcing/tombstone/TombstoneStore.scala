package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ResourceRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.EncoderOps

import java.time.Instant

object TombstoneStore {

  private val logger = Logger[TombstoneStore.type]

  /**
    * Saves a tombstone for the given entity for the provided tag so that indexing processes can take it into account
    */
  def save[S <: ScopedState](tpe: EntityType, state: S, removedTag: UserTag): ConnectionIO[Unit] =
    sql"""
         | INSERT INTO public.scoped_tombstones (
         |  type,
         |  org,
         |  project,
         |  id,
         |  tag,
         |  cause,
         |  instant
         | )
         | VALUES (
         |  $tpe,
         |  ${state.organization},
         |  ${state.project.project},
         |  ${state.id},
         |  ${removedTag.value},
         |  ${Cause.deleted.asJson},
         |  ${state.updatedAt}
         | )""".stripMargin.update.run.void

  /**
    * Saves a tombstone when an entity is updated and some of its types have been removed and/or it has been validated
    * against a new schema
    */
  def save[S <: ScopedState](tpe: EntityType, original: Option[S], newState: S): ConnectionIO[Unit] =
    Cause.diff(original, newState).fold(().pure[ConnectionIO]) { cause =>
      sql"""
           | INSERT INTO public.scoped_tombstones (
           |  type,
           |  org,
           |  project,
           |  id,
           |  tag,
           |  cause,
           |  instant
           | )
           | VALUES (
           |  $tpe,
           |  ${newState.organization},
           |  ${newState.project.project},
           |  ${newState.id},
           |  ${Tag.latest},
           |  ${cause.asJson},
           |  ${newState.updatedAt}
           | )""".stripMargin.update.run.void
    }

  def deleteExpired(instant: Instant, xas: Transactors): IO[Unit] =
    sql"""DELETE FROM public.scoped_tombstones WHERE instant < $instant""".update.run
      .transact(xas.write)
      .flatTap { deleted =>
        IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted tombstones."))
      }
      .void

  final private[tombstone] case class Cause(deleted: Boolean, types: Set[Iri], schema: Option[ResourceRef])

  private[tombstone] object Cause {

    val deleted: Cause = Cause(deleted = true, Set.empty, None)

    implicit val causeCodec: Codec[Cause] = {
      implicit val configuration: Configuration = Configuration.default
      deriveConfiguredCodec[Cause]
    }

    implicit val causeGet: Get[Cause] = pgDecoderGetT[Cause]

    def diff(types: Set[Iri], schema: Option[ResourceRef]): Cause = Cause(deleted = false, types, schema)

    def diff[S <: ScopedState](original: Option[S], newState: S): Option[Cause] =
      original.flatMap { o =>
        val removedTypes = o.types.diff(newState.types)
        Option.when(removedTypes.nonEmpty)(Cause.diff(removedTypes, None))
      }

  }

  private val metadata = ProjectionMetadata("system", "delete-old-tombstones", None, None)

  /**
    * Creates a [[PurgeProjection]] to schedule in the supervisor the deletion of old tombstones.
    */
  def deleteExpired(config: PurgeConfig, xas: Transactors): PurgeProjection =
    PurgeProjection(metadata, config, TombstoneStore.deleteExpired(_, xas))

}
