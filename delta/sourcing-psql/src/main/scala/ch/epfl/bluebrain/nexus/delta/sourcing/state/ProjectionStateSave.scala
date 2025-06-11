package ch.epfl.bluebrain.nexus.delta.sourcing.state

import doobie.ConnectionIO

/**
  * Allows to save states as a projection in another table
  */
final case class ProjectionStateSave[Id, S](insert: (Id, S) => ConnectionIO[Unit], delete: Id => ConnectionIO[Unit])

object ProjectionStateSave {

  def noop[Id, S]: ProjectionStateSave[Id, S] = ProjectionStateSave(
    (_, _) => doobie.free.connection.unit,
    _ => doobie.free.connection.unit
  )

}
