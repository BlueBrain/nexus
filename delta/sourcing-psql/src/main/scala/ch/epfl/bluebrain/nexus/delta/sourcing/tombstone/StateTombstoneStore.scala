package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, ResourceRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.StateTombstoneStore.{logger, Cause, StateTombstone}
import doobie._
import doobie.syntax.all._
import doobie.postgres.implicits._
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.EncoderOps

import java.time.Instant

final class StateTombstoneStore(xas: Transactors) {

  def count: IO[Long] =
    sql"""SELECT count(*) FROM public.scoped_tombstones""".query[Long].unique.transact(xas.read)

  /**
    * Saves a tombstone for each of the provided tags so that indexing processes can take them into account
    */
  def save[S <: ScopedState](tpe: EntityType, state: S, removedTags: List[Tag]): ConnectionIO[Unit] =
    removedTags.traverse { tag => save(tpe, state, tag) }.void

  /**
    * Saves a tombstone for the given entity for the provided tag so that indexing processes can take it into account
    */
  def save[S <: ScopedState](tpe: EntityType, state: S, removedTag: Tag): ConnectionIO[Unit] =
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

  def deleteExpired(instant: Instant): IO[Unit] =
    sql"""DELETE FROM public.scoped_tombstones WHERE instant < $instant""".update.run
      .transact(xas.write)
      .flatTap { deleted =>
        IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted tombstones."))
      }
      .void

  /**
    * Returns a tombstone for the given project/id (meant for tests as in practice, a resource can be created/deleted
    * and tagged/untagged several times and get several tombstones)
    */
  def unsafeGet(project: ProjectRef, id: Iri, tag: Tag): IO[Option[StateTombstone]] =
    sql"""|SELECT type, org, project, id, tag, cause, instant
          |FROM public.scoped_tombstones
          |WHERE org = ${project.organization} and project= ${project.project} and id = $id and tag = $tag
          |""".stripMargin.query[StateTombstone].option.transact(xas.read)
}

object StateTombstoneStore {

  private val logger = Logger[StateTombstoneStore.type]

  final case class StateTombstone(
      entityType: EntityType,
      project: ProjectRef,
      id: Iri,
      tag: Tag,
      cause: Cause,
      instant: Instant
  )

  object StateTombstone {
    implicit val tombstoneRead: Read[StateTombstone] = {
      implicit val v: Get[Cause] = pgDecoderGetT[Cause]
      Read[(EntityType, Label, Label, Iri, Tag, Cause, Instant)].map {
        case (tpe, org, project, id, tag, cause, instant) =>
          StateTombstone(tpe, ProjectRef(org, project), id, tag, cause, instant)
      }
    }
  }

  final case class Cause(deleted: Boolean, types: Set[Iri], schema: Option[ResourceRef])

  object Cause {

    val deleted: Cause = Cause(deleted = true, Set.empty, None)

    implicit val causeCodec: Codec[Cause] = {
      implicit val configuration: Configuration = Configuration.default
      deriveConfiguredCodec[Cause]
    }

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
  def deleteExpired(config: PurgeConfig, xas: Transactors): PurgeProjection = {
    val tombstoneStore = new StateTombstoneStore(xas)
    PurgeProjection(metadata, config, tombstoneStore.deleteExpired)
  }

}
