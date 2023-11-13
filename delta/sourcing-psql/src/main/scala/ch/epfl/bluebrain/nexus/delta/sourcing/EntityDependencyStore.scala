package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.{DependsOn, ReferencedBy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, Tag}
import doobie._
import doobie.implicits._
import doobie.util.Put
import io.circe.{Decoder, Json}

/**
  * Allows to declare dependencies between entities in the system
  */
object EntityDependencyStore {

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  /**
    * Delete dependencies for the provided id in the given project
    */
  def delete(ref: ProjectRef, id: Iri): ConnectionIO[Unit] =
    sql"""DELETE FROM entity_dependencies WHERE org = ${ref.organization} AND project = ${ref.project} AND id = $id""".stripMargin.update.run.void

  /**
    * Delete all dependencies for the given project
    */
  def deleteAll(ref: ProjectRef): ConnectionIO[Unit] =
    sql"""DELETE FROM entity_dependencies WHERE org = ${ref.organization} AND project = ${ref.project}""".stripMargin.update.run.void

  /**
    * Save dependencies for the provided id in the given project
    */
  def save(ref: ProjectRef, id: Iri, dependencies: Set[DependsOn]): ConnectionIO[Unit] =
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
  def directDependencies[Id](ref: ProjectRef, id: Id, xas: Transactors)(implicit put: Put[Id]): IO[Set[DependsOn]] =
    sql"""
         | SELECT target_org, target_project, target_id
         | FROM entity_dependencies
         | WHERE org = ${ref.organization}
         | AND project = ${ref.project}
         | AND id = $id""".stripMargin
      .query[(Label, Label, Iri)]
      .map { case (org, proj, id) => DependsOn(ProjectRef(org, proj), id) }
      .to[Set]
      .transact(xas.read)

  /**
    * Get direct references from other projects for the given project
    */
  def directExternalReferences(ref: ProjectRef, xas: Transactors): IO[Set[ReferencedBy]] =
    sql"""
         | SELECT org, project, id
         | FROM entity_dependencies
         | WHERE NOT (
         |   org             = ${ref.organization}
         |   AND project     = ${ref.project}
         | )
         | AND   target_org     = ${ref.organization}
         | AND   target_project = ${ref.project}""".stripMargin
      .query[(Label, Label, Iri)]
      .map { case (org, proj, id) => ReferencedBy(ProjectRef(org, proj), id) }
      .to[Set]
      .transact(xas.read)

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
  def recursiveDependencies[Id](ref: ProjectRef, id: Id, xas: Transactors)(implicit put: Put[Id]): IO[Set[DependsOn]] =
    sql"""
         | ${recursiveDependencies(ref, id)}
         | SELECT org, project, id  from recursive_dependencies
       """.stripMargin
      .query[(Label, Label, Iri)]
      .map { case (org, proj, id) => DependsOn(ProjectRef(org, proj), id) }
      .to[Set]
      .transact(xas.read)

  /**
    * Get and decode latest state values for direct dependencies for the provided id in the given project
    */
  def decodeDirectDependencies[Id, A](ref: ProjectRef, id: Id, xas: Transactors)(implicit
      put: Put[Id],
      decoder: Decoder[A]
  ): IO[List[A]] =
    sql"""
         | SELECT s.value
         | FROM entity_dependencies d, scoped_states s
         | WHERE d.org = ${ref.organization}
         | AND d.project = ${ref.project}
         | AND d.id = $id
         | AND s.org = d.target_org
         | AND s.project = d.target_project
         | AND tag = ${Tag.latest}
         | AND s.id = d.target_id""".stripMargin
      .query[Json]
      .to[List]
      .transact(xas.read)
      .flatMap { rows =>
        IO.fromEither(rows.traverse(_.as[A]))
      }

  /**
    * Get and decode latest state values for all dependencies for the provided id in the given project
    */
  def decodeRecursiveDependencies[Id, A](ref: ProjectRef, id: Id, xas: Transactors)(implicit
      put: Put[Id],
      decoder: Decoder[A]
  ): IO[List[A]] =
    sql"""
         | ${recursiveDependencies(ref, id)}
         | SELECT s.value
         | FROM recursive_dependencies d, scoped_states s
         | WHERE s.org = d.org
         | AND s.project = d.project
         | AND tag = ${Tag.latest}
         | AND s.id = d.id
       """.stripMargin
      .query[Json]
      .to[List]
      .transact(xas.read)
      .flatMap { rows =>
        IO.fromEither(rows.traverse(_.as[A]))
      }
}
