package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import doobie._
import doobie.implicits._
import monix.bio.IO

import scala.annotation.nowarn

object EntityCheck {

  def raiseMissingOrDeprecated[Id, E](
      tpe: EntityType,
      refs: Set[(ProjectRef, Id)],
      onUnknownOrDeprecated: Set[(ProjectRef, Id)] => E,
      xas: Transactors
  )(implicit @nowarn("cat=unused") getId: Get[Id], putId: Put[Id]): IO[E, Unit] = {
    val or: Fragment = refs
      .map { case (p, id) =>
        fr"org = ${p.organization} AND project = ${p.project}  AND id = $id AND tag = ${Latest.value} AND deprecated = false"
      }
      .reduceLeft(Fragments.or(_, _))
    sql"""SELECT org, project, id FROM scoped_states WHERE type = $tpe and $or"""
      .query[(Label, Label, Id)]
      .map { case (org, proj, id) =>
        ProjectRef(org, proj) -> id
      }
      .to[Set]
      .transact(xas.read)
      .hideErrors
      .flatMap { result =>
        IO.raiseWhen(result.size < refs.size) {
          onUnknownOrDeprecated(refs.diff(result))
        }
      }
  }
}
