package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.{QuotaReached, QuotasDisabled}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection, QuotasConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ProjectsCounts, Quotas}
import monix.bio.{IO, UIO}

final class QuotasImpl(
    projects: Projects,
    projectsCounts: ProjectsCounts
)(implicit quotaConfig: QuotasConfig, serviceAccountConfig: ServiceAccountConfig)
    extends Quotas {

  override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = {
    projects.fetchProject[QuotaRejection](ref) >> IO.fromEither(quotasFromConfig(ref))
  }.named("fetchQuota", "quotas")

  override def reachedForResources[R](
      ref: ProjectRef,
      subject: Subject
  )(implicit m: Mapper[QuotaReached, R]): IO[R, Unit] =
    IO.unless(subject == serviceAccountConfig.value.subject) {
      for {
        quota     <- UIO.delay(quotasFromConfig(ref).toOption)
        countsOpt <- projectsCounts.get(ref)
        count      = countsOpt.getOrElse(ProjectCount.emptyEpoch)
        _         <- quotaReached(count.resources, quota.flatMap(_.resources)) { QuotaResourcesReached(ref, _) }
      } yield ()
    }

  override def reachedForEvents[R](
      ref: ProjectRef,
      subject: Subject
  )(implicit mapper: Mapper[QuotaReached, R]): IO[R, Unit] =
    IO.unless(subject == serviceAccountConfig.value.subject) {
      for {
        quota     <- UIO.delay(quotasFromConfig(ref).toOption)
        countsOpt <- projectsCounts.get(ref)
        count      = countsOpt.getOrElse(ProjectCount.emptyEpoch)
        _         <- quotaReached(count.events, quota.flatMap(_.events)) { QuotaEventsReached(ref, _) }
      } yield ()
    }

  private def quotaReached[R](current: Long, config: Option[Int])(
      onQuotaReached: Int => QuotaReached
  )(implicit m: Mapper[QuotaReached, R]) =
    config match {
      case Some(value) if current >= value => IO.raiseError(m.to(onQuotaReached(value)))
      case _                               => IO.unit
    }

  private def quotasFromConfig(ref: ProjectRef) =
    quotaConfig.lookup(ref).toRight(QuotasDisabled(ref)).map(Quota(_))
}
