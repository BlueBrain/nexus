package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, Tag}
import doobie._
import doobie.implicits._

import scala.annotation.nowarn

object EntityCheck {

  /**
    * Allows to find the [[EntityType]] of the resource with the given [[Id]] in the given project
    */
  def findType[Id](id: Id, project: ProjectRef, xas: Transactors)(implicit putId: Put[Id]): IO[Option[EntityType]] =
    sql"""
         | SELECT type
         | FROM public.scoped_states
         | WHERE org = ${project.organization}
         | AND project = ${project.project}
         | AND id = $id
         | AND tag = ${Tag.latest}
         |""".stripMargin.query[EntityType].option.transact(xas.readCE)

  /**
    * Raises the defined error if at least one of the provided references does not exist or is deprecated
    * @param tpe
    *   the type of the different resources
    * @param refs
    *   the references to test
    * @param onUnknownOrDeprecated
    *   the error to raise on the failiing references
    */
  def raiseMissingOrDeprecated[Id, E <: Throwable](
      tpe: EntityType,
      refs: Set[(ProjectRef, Id)],
      onUnknownOrDeprecated: Set[(ProjectRef, Id)] => E,
      xas: Transactors
  )(implicit @nowarn("cat=unused") getId: Get[Id], putId: Put[Id]): IO[Unit] = {
    val fragments: Seq[Fragment] = refs.map { case (p, id) =>
      fr"org = ${p.organization} AND project = ${p.project}  AND id = $id AND tag = ${Latest.value} AND deprecated = false"
    }.toSeq
    val or: Fragment             = Fragments.or(fragments: _*)

    sql"""SELECT org, project, id FROM public.scoped_states WHERE type = $tpe and $or"""
      .query[(Label, Label, Id)]
      .map { case (org, proj, id) =>
        ProjectRef(org, proj) -> id
      }
      .to[Set]
      .transact(xas.readCE)
      .flatMap { result =>
        IO.raiseWhen(result.size < refs.size) {
          onUnknownOrDeprecated(refs.diff(result))
        }
      }
  }
}
