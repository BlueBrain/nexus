package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import fs2.Stream
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant

class ProjectStatsCheckSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  test("Should init the extra tables") {
    MigrationCheckHelper.initTables(xas)
  }

  test("Save the stats for the projects") {
    val project1                                          = ProjectRef.unsafe("org", "proj1")
    val project2                                          = ProjectRef.unsafe("org", "proj2")
    def fetchStats: ProjectRef => Task[ProjectStatistics] = {
      case `project1` => Task.pure(ProjectStatistics(10L, 5L, Instant.EPOCH))
      case `project2` => Task.pure(ProjectStatistics(20L, 10L, Instant.EPOCH))
      case _          => Task.raiseError(new IllegalArgumentException("Not a valid project ref"))
    }

    def fetchStats2: ProjectRef => Task[ProjectStatistics] = {
      case `project1` => Task.pure(ProjectStatistics(100L, 50L, Instant.EPOCH))
      case `project2` => Task.pure(ProjectStatistics(200L, 100L, Instant.EPOCH))
      case _          => Task.raiseError(new IllegalArgumentException("Not a valid project ref"))
    }

    def fetchProjects: Stream[Task, ProjectRef] = Stream.emits(List(project1, project2))

    val check = new ProjectsStatsCheck(fetchProjects, fetchStats, fetchStats2, xas)

    for {
      _ <- check.run.compile.drain
      _ <- checkStats(project1).assert((10L, 100, 5L, 50L))
      _ <- checkStats(project2).assert((20L, 200L, 10L, 100L))
    } yield ()
  }

  private def checkStats(project: ProjectRef) =
    sql"""SELECT event_count_1_7,  event_count_1_8, resource_count_1_7, resource_count_1_8 FROM public.migration_project_count WHERE project = $project"""
      .query[(Long, Long, Long, Long)]
      .unique
      .transact(xas.read)

}
