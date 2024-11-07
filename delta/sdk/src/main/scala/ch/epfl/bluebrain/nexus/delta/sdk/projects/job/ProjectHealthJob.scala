package ch.epfl.bluebrain.nexus.delta.sdk.projects.job

import cats.effect.IO
import cats.effect.std.Env
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectHealer, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream

trait ProjectHealthJob

object ProjectHealthJob extends ProjectHealthJob {
  private val logger = Logger[ProjectHealthJob]

  def healTrigger: IO[Boolean] =
    Env[IO].get("HEAL_PROJECTS").map(_.getOrElse("false").toBoolean)

  private[job] def run(currentProjects: Stream[IO, ProjectRef], projectHealer: ProjectHealer): IO[Unit] =
    currentProjects
      .evalMap { projectRef =>
        projectHealer.heal(projectRef).recoverWith { err =>
          logger.error(err)(s"Project '$projectRef' could not be heal because of : ${err.getMessage}.")
        }
      }
      .compile
      .drain

  def apply(projects: Projects, projectHealer: ProjectHealer): IO[ProjectHealthJob.type] =
    healTrigger
      .flatMap {
        case true => {
          logger.info("Starting Nexus automatic project healing.") >>
            run(projects.currentRefs, projectHealer) >>
            logger.info("Nexus automatic healing has completed.")
        }
        case false => logger.info("Nexus automatic project healingi is disabled.")
      }
      .as(ProjectHealthJob)
}
