package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import doobie.syntax.all._

object ScopedStateQueries {

  def distinctProjects(implicit xas: Transactors): IO[Set[ProjectRef]] =
    sql"""SELECT distinct org, project from scoped_states"""
      .query[(Label, Label)]
      .map { case (org, proj) =>
        ProjectRef(org, proj)
      }
      .to[Set]
      .transact(xas.read)

}
