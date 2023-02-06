package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream
import monix.bio.Task
import doobie.implicits._

class ProjectsStatsCheck(
    fetchProjects: Stream[Task, ProjectRef],
    fetchProjectStats17: ProjectRef => Task[ProjectStatistics],
    fetchProjectStats18: ProjectRef => Task[ProjectStatistics],
    xas: Transactors
) {

  def run =
    fetchProjects.evalMap { project =>
      {
        for {
          stats17 <- fetchProjectStats17(project)
          stats18 <- fetchProjectStats18(project)
          _       <- saveStats(project, stats17, stats18)
        } yield ()
      }.onErrorHandleWith(e => saveStatsError(project, e))
    }

  private def saveStats(project: ProjectRef, stats17: ProjectStatistics, stats18: ProjectStatistics) =
    sql"""INSERT INTO public.migration_project_count (project, event_count_1_7, event_count_1_8, resource_count_1_7, resource_count_1_8)
         |VALUES (
         |   $project, ${stats17.events}, ${stats18.events}, ${stats17.resources}, ${stats18.resources}
         |)
         |ON CONFLICT (project)
         |DO UPDATE set
         |  event_count_1_7 = EXCLUDED.event_count_1_7,
         |  event_count_1_8 = EXCLUDED.event_count_1_8,
         |  resource_count_1_7 = EXCLUDED.resource_count_1_7,
         |  resource_count_1_8 = EXCLUDED.resource_count_1_8,
         |  error = NULL
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

  private def saveStatsError(project: ProjectRef, error: Throwable) =
    sql"""INSERT INTO public.migration_project_count (project, error)
         |VALUES (
         |   $project, ${error.getMessage}
         |)
         |ON CONFLICT (project)
         |DO UPDATE set
         |  event_count_1_7 = NULL,
         |  event_count_1_8 = NULL,
         |  resource_count_1_7 = NULL,
         |  resource_count_1_8 = NULL,
         |  error = EXCLUDED.error
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
}
