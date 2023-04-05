package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.instantSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import monix.bio.UIO

import java.time.{Duration, Instant}

object ProjectDeletionLogic {
  implicit private class BooleanTaskOps(val left: Boolean) extends AnyVal {
    def `<||>`(right: UIO[Boolean]): UIO[Boolean] = {
      if (left) {
        UIO.pure(true)
      } else {
        right
      }
    }

    def `<&&>`(right: UIO[Boolean]): UIO[Boolean] = {
      if (left) {
        right
      } else {
        UIO.pure(false)
      }
    }
  }
  def projectDeletionPass(
      allProjects: UIO[Seq[ProjectResource]],
      deleteProject: ProjectResource => UIO[Unit],
      config: ProjectDeletionConfig,
      lastEventTime: (ProjectResource, Instant) => UIO[Instant]
  ): UIO[Unit] = {

    def isIncluded(pr: ProjectResource): Boolean = {
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def notExcluded(pr: ProjectResource): Boolean = {
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def deletableDueToDeprecation(pr: ProjectResource): Boolean = {
      config.deleteDeprecatedProjects && pr.deprecated
    }

    def deletableDueToBeingIdle(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      projectIsIdle(pr, now) <&&> resourcesAreIdle(pr, now)
    }

    def projectIsIdle(pr: ProjectResource, now: Instant) = {
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds
    }

    def resourcesAreIdle(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      lastEventTime(pr, now).map(_.isBefore(now.minus(Duration.ofMillis(config.idleInterval.toMillis))))
    }

    def shouldBeDeleted(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      (isIncluded(pr) && notExcluded(pr) && !pr.value.markedForDeletion) <&&> (deletableDueToDeprecation(
        pr
      ) <||> deletableDueToBeingIdle(pr, now))
    }

    def processProject(pr: ProjectResource, now: Instant): UIO[Unit] = {
      shouldBeDeleted(pr, now).flatMap {
        case true  => deleteProject(pr)
        case false => UIO.unit
      }
    }

    for {
      allProjects <- allProjects
      now         <- IOUtils.instant
      _           <- allProjects.traverse(processProject(_, now))
    } yield {
      ()
    }
  }

}
