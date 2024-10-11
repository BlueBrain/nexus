package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.Location

class ProjectSignalsSuite extends NexusSuite {

  private def assertSignal(signals: ProjectSignals[Int], project: ProjectRef, expected: Option[Boolean])(implicit
      loc: Location
  ) =
    signals.get(project).flatMap(_.traverse(_.get)).assertEquals(expected)

  test("Should init and update the signals accordingly") {
    val project1 = ProjectRef.unsafe("org", "proj1")
    val project2 = ProjectRef.unsafe("org", "proj2")
    val project3 = ProjectRef.unsafe("org", "proj3")
    val project4 = ProjectRef.unsafe("org", "proj4")

    val init = Map(
      project1 -> 1,
      project2 -> 2,
      project3 -> 3
    )

    for {
      signals         <- ProjectSignals[Int]
      _               <- signals.refresh(init, _ > 1)
      _               <- assertSignal(signals, project1, Some(false))
      _               <- assertSignal(signals, project2, Some(true))
      _               <- assertSignal(signals, project3, Some(true))
      _               <- assertSignal(signals, project4, None)
      updates          = Map(project4 -> 4, project1 -> 5)
      _               <- signals.refresh(updates, _ > 2)
      expectedActivity = Map(project1 -> true, project2 -> false, project3 -> true, project4 -> true)
      _               <- signals.activityMap.assertEquals(expectedActivity)
    } yield ()
  }

}
