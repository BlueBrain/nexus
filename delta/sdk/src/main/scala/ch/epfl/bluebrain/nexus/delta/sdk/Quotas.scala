package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection}
import monix.bio.IO

trait Quotas {

  /**
    * Fetches the quotas for a project.
    *
    * @param ref the project reference
    */
  def fetch(ref: ProjectRef): IO[QuotaRejection, Quota]
}
