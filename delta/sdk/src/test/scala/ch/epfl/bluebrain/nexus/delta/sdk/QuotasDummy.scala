package ch.epfl.bluebrain.nexus.delta.sdk
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

object QuotasDummy {

  def alwaysReached: Quotas = new Quotas {
    override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = IO.pure(Quota(Some(0), Some(0)))

    override def reachedForResources[R](
        ref: ProjectRef,
        subject: Subject
    )(implicit mapper: Mapper[QuotaReached, R]): IO[R, Unit] =
      IO.raiseError(mapper.to(QuotaResourcesReached(ref, 0)))

    override def reachedForEvents[R](
        ref: ProjectRef,
        subject: Subject
    )(implicit mapper: Mapper[QuotaReached, R]): IO[R, Unit] =
      IO.raiseError(mapper.to(QuotaEventsReached(ref, 0)))

  }

  def neverReached: Quotas = new Quotas {
    override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = IO.pure(Quota(Some(0), Some(0)))

    override def reachedForResources[R](ref: ProjectRef, subject: Subject)(implicit
        mapper: Mapper[QuotaReached, R]
    ): IO[R, Unit] =
      IO.unit

    override def reachedForEvents[R](ref: ProjectRef, subject: Subject)(implicit
        mapper: Mapper[QuotaReached, R]
    ): IO[R, Unit] =
      IO.unit
  }
}
