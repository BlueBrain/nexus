package ch.epfl.bluebrain.nexus.delta.sdk
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection}
import monix.bio.IO

object QuotasDummy {

  def alwaysReached: Quotas = new Quotas {
    override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = IO.pure(Quota(0))
    override def reachedForResources[R](ref: ProjectRef, subject: Identity.Subject)(implicit
        mapper: Mapper[QuotaReached, R]
    ): IO[R, Unit]                                                 =
      IO.raiseError(mapper.to(QuotaReached(ref, 0)))
  }

  def neverReached: Quotas = new Quotas {
    override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = IO.pure(Quota(0))
    override def reachedForResources[R](ref: ProjectRef, subject: Identity.Subject)(implicit
        mapper: Mapper[QuotaReached, R]
    ): IO[R, Unit]                                                 =
      IO.unit
  }
}
