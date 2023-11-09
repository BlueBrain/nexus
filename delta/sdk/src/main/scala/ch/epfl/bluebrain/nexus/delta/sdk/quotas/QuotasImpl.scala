package ch.epfl.bluebrain.nexus.delta.sdk.quotas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.Quota
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.{QuotaReached, QuotasDisabled}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

final class QuotasImpl(
    projectsStatistics: ProjectsStatistics
)(implicit quotaConfig: QuotasConfig, serviceAccountConfig: ServiceAccountConfig)
    extends Quotas {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent("quotas")

  override def fetch(ref: ProjectRef): IO[Quota] =
    IO.fromEither(quotasFromConfig(ref)).span("fetchQuota")

  override def reachedForResources(
      ref: ProjectRef,
      subject: Subject
  ): IO[Unit] = {
    val quota = quotasFromConfig(ref).toOption
    IO.unlessA(quota.isEmpty || subject == serviceAccountConfig.value.subject) {
      for {
        countsOpt <- projectsStatistics.get(ref)
        _         <- countsOpt
                       .map { count =>
                         quotaReached(count.resources, quota.flatMap(_.resources)) { QuotaResourcesReached(ref, _) }
                       }
                       .getOrElse(IO.unit)
      } yield ()
    }
  }

  override def reachedForEvents(
      ref: ProjectRef,
      subject: Subject
  ): IO[Unit] = {
    val quota = quotasFromConfig(ref).toOption
    IO.unlessA(quota.isEmpty || subject == serviceAccountConfig.value.subject) {
      for {
        countsOpt <- projectsStatistics.get(ref)
        _         <- countsOpt
                       .map { count =>
                         quotaReached(count.events, quota.flatMap(_.events)) { QuotaEventsReached(ref, _) }
                       }
                       .getOrElse(IO.unit)

      } yield ()
    }
  }

  private def quotaReached(current: Long, config: Option[Int])(
      onQuotaReached: Int => QuotaReached
  ) =
    config match {
      case Some(value) if current >= value => IO.raiseError(onQuotaReached(value))
      case _                               => IO.unit
    }

  private def quotasFromConfig(ref: ProjectRef): Either[QuotasDisabled, Quota] =
    quotaConfig.lookup(ref).toRight(QuotasDisabled(ref)).map(Quota(_))
}
