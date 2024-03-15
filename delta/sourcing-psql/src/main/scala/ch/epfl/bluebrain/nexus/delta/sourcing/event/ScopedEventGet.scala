package ch.epfl.bluebrain.nexus.delta.sourcing.event

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import doobie._
import doobie.implicits._

object ScopedEventGet {

  def history[Id: Put, E: Get](tpe: EntityType, ref: ProjectRef, id: Id, to: Option[Int]): doobie.Query0[E] = {
    val select = fr"SELECT value FROM scoped_events" ++
      Fragments.whereAndOpt(
        Some(fr"type = $tpe"),
        Some(fr"org = ${ref.organization}"),
        Some(fr"project = ${ref.project}"),
        Some(fr"id = $id"),
        to.map { t => fr" rev <= $t" }
      ) ++
      fr"ORDER BY rev"
    select.query[E]
  }

}
