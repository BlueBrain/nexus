package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectsCountsDummy
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.{QuotasDisabled, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotasConfig.QuotaConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotasConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class QuotasImplSpec extends AnyWordSpecLike with Matchers with IOValues with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())

  private val org           = Label.unsafe("myorg")
  private val project       = ProjectGen.project("myorg", "myproject")
  private val project2      = ProjectGen.project("myorg", "myproject2")
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: project2 :: Nil).accepted

  implicit private val config            = QuotasConfig(
    resources = Some(100),
    events = Some(150),
    enabled = true,
    Map(project2.ref -> QuotaConfig(resources = Some(200), events = None))
  )
  implicit private val serviceAccountCfg = ServiceAccountConfig(
    ServiceAccount(User("internal", Label.unsafe("sa")))
  )

  private val projectsCounts =
    ProjectsCountsDummy(project.ref -> ProjectCount(events = 10, resources = 8, Instant.EPOCH))

  private val quotas         = new QuotasImpl(projects, projectsCounts)

  "Quotas" should {

    "be fetched from configuration" in {
      quotas.fetch(project.ref).accepted shouldEqual Quota(resources = Some(100), events = Some(150))
      quotas.fetch(project2.ref).accepted shouldEqual Quota(resources = Some(200), events = None)
    }

    "failed to be fetched if project does not exist" in {
      val nonExisting = ProjectRef(Label.unsafe("a"), Label.unsafe("b"))
      quotas.fetch(nonExisting).rejectedWith[WrappedProjectRejection]
    }

    "failed to be fetched if quotas config is disabled" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(enabled = false), serviceAccountCfg)
      quotas.fetch(project.ref).rejectedWith[QuotasDisabled]
    }

    "not be reached" in {
      quotas.reachedForResources(project.ref, Anonymous).accepted
      quotas.reachedForEvents(project.ref, Anonymous).accepted
    }

    "not be reached when disabled" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(enabled = false), serviceAccountCfg)
      quotas.reachedForResources(project.ref, Anonymous).accepted
      quotas.reachedForEvents(project.ref, Anonymous).accepted
    }

    "be reached for resources" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(resources = Some(8)), serviceAccountCfg)
      quotas.reachedForResources(project.ref, Anonymous).rejected shouldEqual QuotaResourcesReached(project.ref, 8)

      quotas.reachedForResources(project.ref, serviceAccountCfg.value.subject).accepted
      quotas.reachedForResources(project2.ref, Anonymous).accepted
    }

    "be reached for events" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(events = Some(10)), serviceAccountCfg)
      quotas.reachedForEvents(project.ref, Anonymous).rejected shouldEqual QuotaEventsReached(project.ref, 10)

      quotas.reachedForEvents(project.ref, serviceAccountCfg.value.subject).accepted
      quotas.reachedForEvents(project2.ref, Anonymous).accepted
    }
  }

}
