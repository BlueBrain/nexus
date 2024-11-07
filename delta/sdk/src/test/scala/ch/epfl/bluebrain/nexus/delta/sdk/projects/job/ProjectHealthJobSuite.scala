package ch.epfl.bluebrain.nexus.delta.sdk.projects.job

import cats.effect.IO
import cats.effect.Ref
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectHealer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

class ProjectHealthJobSuite extends NexusSuite {

  test("Healing should be called for the different projects") {
    val projects = Set(ProjectRef.unsafe("org", "proj"), ProjectRef.unsafe("org2", "proj2"))
    for {
      ref          <- Ref.of[IO, Set[ProjectRef]](Set.empty)
      projectHealer = new ProjectHealer {
                        override def heal(project: ProjectRef): IO[Unit] = ref.update(_ + project)
                      }
      stream        = Stream.iterable(projects)
      _            <- ProjectHealthJob.run(stream, projectHealer)
      _            <- ref.get.assertEquals(projects)
    } yield ()

  }

}
