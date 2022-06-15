package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import doobie.implicits._
import monix.bio.UIO

/**
  * Persistent operations for projections.
  */
trait ProjectionStore {

  /**
    * Saves a projection offset.
    *
    * @param name
    *   the name of the projection
    * @param offset
    *   the offset to save
    */
  def save(name: String, offset: ProjectionOffset): UIO[Unit]

  /**
    * Retrieves a projection offset, defaulting to [[ProjectionOffset.empty]] when not found.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): UIO[ProjectionOffset]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): UIO[Unit]

}

object ProjectionStore {

  def apply(xas: Transactors): ProjectionStore =
    new ProjectionStore {
      override def save(name: String, offset: ProjectionOffset): UIO[Unit] =
        sql"""INSERT INTO projection_offsets (name, value)
             |VALUES ($name, $offset)
             |ON CONFLICT (name)
             |DO UPDATE set value = $offset
             |""".stripMargin.update.run
          .transact(xas.streaming)
          .void
          .hideErrors

      override def offset(name: String): UIO[ProjectionOffset] =
        sql"""SELECT value FROM projection_offsets
             |WHERE name = $name
             |""".stripMargin
          .query[ProjectionOffset]
          .option
          .transact(xas.streaming)
          .hideErrors
          .map(_.getOrElse(ProjectionOffset.empty))

      override def delete(name: String): UIO[Unit] =
        sql"""DELETE FROM projection_offsets
             |WHERE name = $name
             |""".stripMargin.update.run
          .transact(xas.streaming)
          .void
          .hideErrors
    }

}
