package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotasDisabled
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, Quotas}
import monix.bio.IO

final class QuotasImpl(projects: Projects)(implicit config: QuotasConfig) extends Quotas {

  override def fetch(ref: ProjectRef): IO[QuotaRejection, Quota] = {
    projects.fetchProject[QuotaRejection](ref) >> IO.fromEither(quotasFromConfig(ref))
  }.named("fetchQuota", "quotas")

  private def quotasFromConfig(ref: ProjectRef) =
    Either.cond(config.enabled, Quota(config.custom.getOrElse(ref, config.resources)), QuotasDisabled(ref))

}
