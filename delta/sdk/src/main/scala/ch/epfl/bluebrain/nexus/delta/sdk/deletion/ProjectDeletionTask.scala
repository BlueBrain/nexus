package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
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
  def apply(project: ProjectRef)(implicit subject: Subject): Task[ProjectDeletionReport.Stage]

}
