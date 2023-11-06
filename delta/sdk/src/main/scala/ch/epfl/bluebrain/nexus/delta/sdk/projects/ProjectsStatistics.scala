package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import doobie.implicits._
import doobie.postgres.implicits._
import monix.bio.UIO

import java.time.Instant
import scala.concurrent.duration._

trait ProjectsStatistics {

  /**
    * Retrieve the current counts (and latest instant) of events for the passed ''project''
    */
  def get(project: ProjectRef): UIO[Option[ProjectStatistics]]
}

object ProjectsStatistics {

  def apply(xas: Transactors): UIO[ProjectsStatistics] = {
    // TODO make the cache configurable
    KeyValueStore.local[ProjectRef, ProjectStatistics](500, 3.seconds).map { cache => (project: ProjectRef) =>
      cache.getOrElseAttemptUpdate(
        project,
        sql"""
               | SELECT COUNT(id), SUM(rev), MAX(instant) FROM scoped_states
               | WHERE org = ${project.organization} and project = ${project.project} AND tag = ${Tag.Latest.value}
               | """.stripMargin
          .query[(Long, Option[Long], Option[Instant])]
          .unique
          .map {
            case (resources, Some(events), Some(instant)) => Some(ProjectStatistics(events, resources, instant))
            case (_, _, _)                                => None
          }
          .transact(xas.read)
          .hideErrors
      )
    }
  }
}
