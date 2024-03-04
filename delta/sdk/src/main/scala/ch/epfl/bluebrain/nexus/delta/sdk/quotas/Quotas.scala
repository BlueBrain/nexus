package ch.epfl.bluebrain.nexus.delta.sdk.quotas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.Quota
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.QuotasDisabled
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait Quotas {

  /**
    * Fetches the quotas for a project.
    *
    * @param ref
    *   the project reference
    */
  def fetch(ref: ProjectRef): IO[Quota]

  /**
    * Verify that the quotas for resources on the current project haven't been reached.
    *
    * @param ref
    *   th project reference
    * @return
    *   Returns in the regular channel if no quotas have been reached or in the error channel otherwise
    */
  def reachedForResources(ref: ProjectRef, subject: Subject): IO[Unit]

  /**
    * Verify that the quotas for events on the current project haven't been reached.
    *
    * @param ref
    *   th project reference
    * @return
    *   Returns in the regular channel if no quotas have been reached or in the error channel otherwise
    */
  def reachedForEvents(ref: ProjectRef, subject: Subject): IO[Unit]

}

object Quotas {

  val disabled = new Quotas {

    override def fetch(ref: ProjectRef): IO[Quota] = IO.raiseError(QuotasDisabled(ref))

    override def reachedForResources(ref: ProjectRef, subject: Subject): IO[Unit] = IO.unit

    override def reachedForEvents(ref: ProjectRef, subject: Subject): IO[Unit] = IO.unit
  }

}
