package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection}
import monix.bio.IO

trait Quotas {

  /**
    * Fetches the quotas for a project.
    *
    * @param ref the project reference
    */
  def fetch(ref: ProjectRef): IO[QuotaRejection, Quota]

  /**
    * Verify that the quotas for resources on the current project haven't been reached.
    *
    * @param ref th project reference
    * @return Returns in the regular channel if no quotas have been reached or in the error channel otherwise
    */
  def reachedForResources[R](ref: ProjectRef, subject: Subject)(implicit mapper: Mapper[QuotaReached, R]): IO[R, Unit]

}
