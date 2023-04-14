package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

/**
  * Task to be completed during project deletion
  */
trait ProjectDeletionTask {

  /**
    * Perform the deletion task for the given project on behalf of the given user
    */
  def apply(project: ProjectRef)(implicit subject: Subject): Task[ProjectDeletionTask.Result]

}

object ProjectDeletionTask {

  /**
    * @param name
    *   name of the task
    * @param log
    *   log of the performed operations
    */
  final case class Result(name: String, log: Vector[String]) {
    def ++(line: String): Result = copy(log = log :+ line)
  }

  object Result {
    def empty(name: String): Result = Result(name, Vector.empty)
  }

  final case class Error(message: String) extends SDKError {
    override def getMessage: String = message
  }

  object Error {
    def apply(throwable: Throwable): Error = Error(throwable.getMessage)
  }
}
