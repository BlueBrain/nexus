package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, Label, ProjectRef}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.Put
import io.circe.{Decoder, Json}
import monix.bio.{Task, UIO}

/**
  * Allows to declare dependencies between entities in the system
  */
object EntityDependencyStore {

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  /**
    * Delete dependencies for the provided id in the given project
    */
  def delete[Id](ref: ProjectRef, id: Id)(implicit put: Put[Id]): ConnectionIO[Unit] =
    sql"""DELETE FROM entity_dependencies WHERE org = ${ref.organization} AND project = ${ref.project} AND id = $id""".stripMargin.update.run.void

  /**
    * Save dependencies for the provided id in the given project
    */
  def save[Id](ref: ProjectRef, id: Id, dependencies: Set[EntityDependency])(implicit
      put: Put[Id]
  ): ConnectionIO[Unit] =
    dependencies.foldLeft(noop) { case (acc, dependency) =>
      acc >>
        sql"""
           | INSERT INTO entity_dependencies (
           |  org,
           |  project,
           |  id,
           |  target_org,
           |  target_project,
           |  target_id
           | )
           | VALUES (
           |  ${ref.organization},
           |  ${ref.project},
           |  $id,
           |  ${dependency.project.organization},
           |  ${dependency.project.project},
           |  ${dependency.id}
           | )
            """.stripMargin.update.run.void
    }

  /**
    * Get direct dependencies for the provided id in the given project
    */
  def list[Id](ref: ProjectRef, id: Id, xas: Transactors)(implicit put: Put[Id]): UIO[Set[EntityDependency]] =
    sql"""
         | SELECT target_org, target_project, target_id
         | FROM entity_dependencies
         | WHERE org = ${ref.organization}
         | AND project = ${ref.project}
         | AND id = $id""".stripMargin
      .query[(Label, Label, String)]
      .map { case (org, proj, id) =>
        EntityDependency(ProjectRef(org, proj), id)
      }
      .to[Set]
      .transact(xas.read)
      .hideErrors

  private def recursiveDependencies[Id](ref: ProjectRef, id: Id)(implicit put: Put[Id]) =
    fr"""
         | WITH RECURSIVE recursive_dependencies(org, project, id) AS (
         | SELECT target_org, target_project, target_id
         | FROM entity_dependencies
         | WHERE org = ${ref.organization} and project = ${ref.project} and id = $id
         | UNION
         | SELECT e.target_org, e.target_project, e.target_id
         | FROM entity_dependencies e, recursive_dependencies r
         | WHERE e.org = r.org
         | AND e.project = r.project
         | AND e.id = r.id
         | ) CYCLE id SET is_cycle USING path""".stripMargin

  /**
    * Get all dependencies for the provided id in the given project
    */
  def recursiveList[Id](ref: ProjectRef, id: Id, xas: Transactors)(implicit put: Put[Id]): UIO[Set[EntityDependency]] =
    sql"""
         | ${recursiveDependencies(ref, id)}
         | SELECT org, project, id  from recursive_dependencies
       """.stripMargin
      .query[(Label, Label, String)]
      .map { case (org, proj, id) =>
        EntityDependency(ProjectRef(org, proj), id)
      }
      .to[Set]
      .transact(xas.read)
      .hideErrors

  /**
    * Get and decode state values for direct dependencies for the provided id in the given project
    */
  def decodeList[Id, A](ref: ProjectRef, id: Id, xas: Transactors)(implicit
      put: Put[Id],
      decoder: Decoder[A]
  ): UIO[List[A]] =
    sql"""
         | SELECT s.value
         | FROM entity_dependencies d, scoped_states s
         | WHERE d.org = ${ref.organization}
         | AND d.project = ${ref.project}
         | AND d.id = $id
         | AND s.org = d.target_org
         | AND s.project = d.target_project
         | AND s.id = d.target_id""".stripMargin
      .query[Json]
      .to[List]
      .transact(xas.read)
      .flatMap { rows =>
        Task.fromEither(rows.traverse(_.as[A]))
      }
      .hideErrors

  /**
    * Get and decode state values for all dependencies for the provided id in the given project
    */
  def decodeRecursiveList[Id, A](ref: ProjectRef, id: Id, xas: Transactors)(implicit
      put: Put[Id],
      decoder: Decoder[A]
  ): UIO[List[A]] =
    sql"""
         | ${recursiveDependencies(ref, id)}
         | SELECT s.value
         | FROM recursive_dependencies d, scoped_states s
         | WHERE s.org = d.org
         | AND s.project = d.project
         | AND s.id = d.id
       """.stripMargin
      .query[Json]
      .to[List]
      .transact(xas.read)
      .flatMap { rows =>
        Task.fromEither(rows.traverse(_.as[A]))
      }
      .hideErrors
}
