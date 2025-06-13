package ai.senscience.nexus.delta.projectdeletion

import ai.senscience.nexus.delta.projectdeletion.model.ProjectDeletionConfig
import cats.Semigroup
import cats.data.NonEmptyList
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.instantSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource

import java.time.{Duration, Instant}

object ShouldDeleteProject {

  val andSemigroup: Semigroup[Boolean] = (x: Boolean, y: Boolean) => x && y
  val orSemigroup: Semigroup[Boolean]  = (x: Boolean, y: Boolean) => x || y

  def apply(
      config: ProjectDeletionConfig,
      lastEventTime: (ProjectResource, Instant) => IO[Instant],
      clock: Clock[IO]
  ): ProjectResource => IO[Boolean] = (pr: ProjectResource) => {

    def isIncluded(pr: ProjectResource): Boolean = {
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def notExcluded(pr: ProjectResource): Boolean = {
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def deletableDueToDeprecation(pr: ProjectResource): Boolean = {
      config.deleteDeprecatedProjects && pr.deprecated
    }

    def deletableDueToBeingIdle(pr: ProjectResource): IO[Boolean] = {
      implicit val and = andSemigroup
      for {
        now  <- clock.realTimeInstant
        idle <- NonEmptyList.of(IO.pure(projectIsIdle(pr, now)), resourcesAreIdle(pr, now)).reduce
      } yield {
        idle
      }
    }

    def projectIsIdle(pr: ProjectResource, now: Instant) = {
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds
    }

    def resourcesAreIdle(pr: ProjectResource, now: Instant): IO[Boolean] = {
      lastEventTime(pr, now).map(_.isBefore(now.minus(Duration.ofMillis(config.idleInterval.toMillis))))
    }

    def alreadyDeleted(pr: ProjectResource): Boolean = {
      pr.value.markedForDeletion
    }

    def worthyOfDeletion(pr: ProjectResource): IO[Boolean] = {
      implicit val or = orSemigroup
      NonEmptyList.of(IO.pure(deletableDueToDeprecation(pr)), deletableDueToBeingIdle(pr)).reduce
    }

    implicit val and = andSemigroup
    NonEmptyList
      .of(
        IO.pure(isIncluded(pr)),
        IO.pure(notExcluded(pr)),
        IO.pure(!alreadyDeleted(pr)),
        worthyOfDeletion(pr)
      )
      .reduce
  }
}
