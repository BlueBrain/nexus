package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import doobie.implicits._
import doobie.{ConnectionIO, Get, Put}

object GlobalStateGet {

  def apply[Id: Put, S: Get](tpe: EntityType, id: Id): ConnectionIO[Option[S]] =
    sql"""SELECT value FROM global_states WHERE type = $tpe AND id = $id"""
      .query[S]
      .option
}
