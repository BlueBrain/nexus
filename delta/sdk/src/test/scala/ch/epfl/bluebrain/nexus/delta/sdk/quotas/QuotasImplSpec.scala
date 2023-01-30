package ch.epfl.bluebrain.nexus.delta.sdk.quotas

import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.QuotasConfig.QuotaConfig
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.Quota
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.QuotasDisabled
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class QuotasImplSpec extends AnyWordSpecLike with Matchers with IOValues with ConfigFixtures {

  private val project  = ProjectRef.unsafe("myorg", "myproject")
  private val project2 = ProjectRef.unsafe("myorg", "myproject2")

  implicit private val config: QuotasConfig                    = QuotasConfig(
    resources = Some(100),
    events = Some(150),
    enabled = true,
    Map(project2 -> QuotaConfig(resources = Some(200), events = None))
  )
  implicit private val serviceAccountCfg: ServiceAccountConfig = ServiceAccountConfig(
    ServiceAccount(User("internal", Label.unsafe("sa")))
  )

  private val projectStatistics: ProjectsStatistics = {
    case `project` => UIO.some(ProjectStatistics(events = 10, resources = 8, Instant.EPOCH))
    case _         => UIO.none
  }

  private val quotas = new QuotasImpl(projectStatistics)

  "Quotas" should {

    "be fetched from configuration" in {
      quotas.fetch(project).accepted shouldEqual Quota(resources = Some(100), events = Some(150))
      quotas.fetch(project2).accepted shouldEqual Quota(resources = Some(200), events = None)
    }

    "failed to be fetched if quotas config is disabled" in {
      val quotas = new QuotasImpl(projectStatistics)(config.copy(enabled = false), serviceAccountCfg)
      quotas.fetch(project).rejectedWith[QuotasDisabled]
    }

    "not be reached" in {
      quotas.reachedForResources(project, Anonymous).accepted
      quotas.reachedForEvents(project, Anonymous).accepted
    }

    "not be reached when disabled" in {
      val quotas = new QuotasImpl(projectStatistics)(config.copy(enabled = false), serviceAccountCfg)
      quotas.reachedForResources(project, Anonymous).accepted
      quotas.reachedForEvents(project, Anonymous).accepted
    }

    "be reached for resources" in {
      val quotas = new QuotasImpl(projectStatistics)(config.copy(resources = Some(8)), serviceAccountCfg)
      quotas.reachedForResources(project, Anonymous).rejected shouldEqual QuotaResourcesReached(project, 8)
      quotas.reachedForResources(project, serviceAccountCfg.value.subject).accepted
      quotas.reachedForResources(project2, Anonymous).accepted
    }

    "be reached for events" in {
      val quotas = new QuotasImpl(projectStatistics)(config.copy(events = Some(10)), serviceAccountCfg)
      quotas.reachedForEvents(project, Anonymous).rejected shouldEqual QuotaEventsReached(project, 10)
      quotas.reachedForEvents(project, serviceAccountCfg.value.subject).accepted
      quotas.reachedForEvents(project2, Anonymous).accepted
    }
  }

}
