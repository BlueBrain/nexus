package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * Allows to save/fetch [[EphemeralState]] from the database
  */
trait EphemeralStateStore[Id, S <: EphemeralState] {

  /**
    * Persist the state
    */
  def save(state: S): ConnectionIO[Unit]

  /**
    * Returns the state
    */
  def get(ref: ProjectRef, id: Id): UIO[Option[S]]
}

object EphemeralStateStore {

  def apply[Id, S <: EphemeralState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      ttl: FiniteDuration,
      xas: Transactors
  )(implicit @nowarn("cat=unused") getId: Get[Id], putId: Put[Id]): EphemeralStateStore[Id, S] =
    new EphemeralStateStore[Id, S] {

      import serializer._

      override def save(state: S): doobie.ConnectionIO[Unit] = {
        val id = extractId(state)
        sql"""
           | INSERT INTO public.ephemeral_states (
           |  type,
           |  org,
           |  project,
           |  id,
           |  value,
           |  instant,
           |  expires
           | )
           | VALUES (
           |  $tpe,
           |  ${state.organization},
           |  ${state.project.project},
           |  $id,
           |  ${state.asJson},
           |  ${state.updatedAt},
           |  ${state.updatedAt.plusMillis(ttl.toMillis)}
           | )
            """.stripMargin
      }.update.run.void

      override def get(ref: ProjectRef, id: Id): UIO[Option[S]] =
        sql"""SELECT value FROM public.ephemeral_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id"""
          .query[Json]
          .option
          .transact(xas.read)
          .flatMap {
            _.traverse { json => IO.fromEither(json.as[S]) }
          }
          .hideErrors
    }

}
