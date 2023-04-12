package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.Semigroup
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.instantSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import monix.bio.UIO

import java.time.{Duration, Instant}

object ShouldDeleteProject {

  val andSemigroup: Semigroup[Boolean] = (x: Boolean, y: Boolean) => x && y
  val orSemigroup: Semigroup[Boolean]  = (x: Boolean, y: Boolean) => x || y

  def apply(
      config: ProjectDeletionConfig,
      lastEventTime: (ProjectResource, Instant) => UIO[Instant]
  ): ProjectResource => UIO[Boolean] = (pr: ProjectResource) => {

    def isIncluded(pr: ProjectResource): Boolean = {
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def notExcluded(pr: ProjectResource): Boolean = {
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def deletableDueToDeprecation(pr: ProjectResource): Boolean = {
      config.deleteDeprecatedProjects && pr.deprecated
    }

    def deletableDueToBeingIdle(pr: ProjectResource): UIO[Boolean] = {
      implicit val and = andSemigroup
      for {
        now  <- IOUtils.instant
        idle <- NonEmptyList.of(UIO.pure(projectIsIdle(pr, now)), resourcesAreIdle(pr, now)).reduce
      } yield {
        idle
      }
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

    def worthyOfDeletion(pr: ProjectResource): UIO[Boolean] = {
      implicit val or = orSemigroup
      NonEmptyList.of(UIO.pure(deletableDueToDeprecation(pr)), deletableDueToBeingIdle(pr)).reduce
    }

    implicit val and = andSemigroup
    NonEmptyList
      .of(
        UIO.pure(isIncluded(pr)),
        UIO.pure(notExcluded(pr)),
        UIO.pure(!alreadyDeleted(pr)),
        worthyOfDeletion(pr)
      )
      .reduce
  }
}
