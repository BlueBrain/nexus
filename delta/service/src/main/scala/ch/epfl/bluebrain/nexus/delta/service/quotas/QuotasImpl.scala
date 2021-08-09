package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
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

  override def reachedForResources[R](ref: ProjectRef, subject: Subject)(implicit
      m: Mapper[QuotaReached, R]
  ): IO[R, Unit] =
    IO.unless(subject == serviceAccountConfig.value.subject) {
      for {
        quota     <- UIO.delay(quotasFromConfig(ref).toOption)
        countsOpt <- projectsCounts.get(ref)
        count      = countsOpt.getOrElse(ProjectCount.emptyEpoch).resources
        result    <-
          IO.fromOption(quota.collect { case q if q.resources <= count => m.to(QuotaReached(ref, q.resources)) }).flip
      } yield result
    }

  private def quotasFromConfig(ref: ProjectRef) =
    Either.cond(
      quotaConfig.enabled,
      Quota(quotaConfig.custom.getOrElse(ref, quotaConfig.resources)),
      QuotasDisabled(ref)
    )
}
