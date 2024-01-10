package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectsHealth
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class ProjectsHealthSuite extends NexusSuite {

  private val unhealthyProject1 = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
  private val unhealthyProject2 = ProjectRef(Label.unsafe("org"), Label.unsafe("proj2"))
  private val unhealthyProjects = Set(unhealthyProject1, unhealthyProject2)

  private val noErrors: List[ScopeInitErrorRow] = List.empty

  private val errors = List(
    // format: off
    ScopeInitErrorRow(1, EntityType("resolver"), unhealthyProject1.organization, unhealthyProject1.project, "message", Instant.EPOCH),
    ScopeInitErrorRow(2, EntityType("view"), unhealthyProject2.organization, unhealthyProject2.project, "message", Instant.EPOCH)
    // format: on
  )

  private def errorStore(errors: List[ScopeInitErrorRow]) = new ScopeInitializationErrorStore {
    override def save(
        entityType: EntityType,
        project: ProjectRef,
        e: ServiceError.ScopeInitializationFailed
    ): IO[Unit] = IO.unit

    override def fetch: IO[List[ScopeInitErrorRow]] =
      IO.pure(errors)

    override def delete(project: ProjectRef): IO[Unit] = IO.unit
  }

  test("return empty set when there are no errors") {
    val projectsHealth = ProjectsHealth(errorStore(noErrors))
    assertIO(projectsHealth.health, Set.empty[ProjectRef])
  }

  test("return list of unhealthy projects when there are errors") {
    val projectsHealth = ProjectsHealth(errorStore(errors))
    assertIO(projectsHealth.health, unhealthyProjects)
  }

}
