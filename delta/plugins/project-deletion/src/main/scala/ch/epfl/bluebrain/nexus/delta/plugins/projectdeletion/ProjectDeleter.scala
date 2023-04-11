package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.Semigroup
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.instantSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import monix.bio.UIO

import java.time.{Duration, Instant}

object ProjectDeleter {

  def projectDeletionPass(
      allProjects: UIO[Seq[ProjectResource]],
      deleteProject: ProjectResource => UIO[Unit],
      config: ProjectDeletionConfig,
      lastEventTime: (ProjectResource, Instant) => UIO[Instant]
  ): UIO[Unit] = {

    val deleter = new ProjectDeleter(deleteProject, config, lastEventTime)

    for {
      allProjects <- allProjects
      now         <- IOUtils.instant
      _           <- allProjects.traverse(deleter.processProject(_, now))
    } yield {
      ()
    }
  }
}

class ProjectDeleter(
    deleteProject: ProjectResource => UIO[Unit],
    config: ProjectDeletionConfig,
    lastEventTime: (ProjectResource, Instant) => UIO[Instant]
) {

  private val andSemigroup: Semigroup[UIO[Boolean]] = (x: UIO[Boolean], y: UIO[Boolean]) =>
    x.flatMap {
      case true  => y
      case false => x
    }
  private val orSemigroup: Semigroup[UIO[Boolean]]  = (x: UIO[Boolean], y: UIO[Boolean]) =>
    x.flatMap {
      case true  => x
      case false => y
    }

  def processProject(pr: ProjectResource, now: Instant) = {

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
      (projectIsIdle(pr, now), resourcesAreIdle(pr, now)).reduce(andSemigroup)
    }

    def projectIsIdle(pr: ProjectResource, now: Instant) = {
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds
    }

    def resourcesAreIdle(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      lastEventTime(pr, now).map(_.isBefore(now.minus(Duration.ofMillis(config.idleInterval.toMillis))))
    }

    def alreadyDeleted(pr: ProjectResource): Boolean = {
      pr.value.markedForDeletion
    }

    def shouldBeDeleted(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      (
        isIncluded(pr),
        notExcluded(pr),
        !alreadyDeleted(pr),
        (deletableDueToDeprecation(pr), deletableDueToBeingIdle(pr, now)).reduce(orSemigroup)
      ).reduce(andSemigroup)
    }

    shouldBeDeleted(pr, now).flatMap {
      case true  => deleteProject(pr)
      case false => UIO.unit
    }
  }
}
