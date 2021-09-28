package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.{ProjectReferenceFinder, ProjectResource, Projects}
import com.typesafe.scalalogging.Logger
import monix.bio.UIO

/**
  * Delete project logic, abstracted out such that it can be stubbed in tests.
  */
trait DeleteProject {

  /**
    * Delete the project represented by the argument resource.
    *
    * @param pr
    *   the project to delete
    */
  def apply(pr: ProjectResource): UIO[Unit]

}

object DeleteProject {

  private val logger: Logger = Logger[ProjectDeletion]

  /**
    * Constructs the default DeleteProject logic that ignores rejections.
    *
    * @param projects
    *   the projects interface
    * @param sub
    *   the subject for which the deletion will be recorded
    * @param prf
    *   the reference finder implementation
    */
  def apply(projects: Projects)(implicit sub: Subject, prf: ProjectReferenceFinder): DeleteProject =
    (pr: ProjectResource) =>
      projects
        .delete(pr.value.ref, pr.rev)
        .flatMap { case (uuid, _) =>
          UIO.delay(logger.info(s"Marked project '${pr.value.ref}' for deletion ($uuid)."))
        }
        .onErrorHandleWith { rejection =>
          UIO.delay(
            logger.error(
              s"Unable to mark project '${pr.value.ref}' for deletion due to '${rejection.reason}'. It will be retried at the next pass."
            )
          )
        }

}
