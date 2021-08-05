package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.Quota
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.{QuotasDisabled, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class QuotasImplSpec extends AnyWordSpecLike with Matchers with IOValues with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())

  private val org           = Label.unsafe("myorg")
  private val project       = ProjectGen.project("myorg", "myproject")
  private val project2      = ProjectGen.project("myorg", "myproject2")
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: project2 :: Nil).accepted

  implicit private val config = QuotasConfig(resources = 100, enabled = true, Map(project2.ref -> 200))
  private val quotas          = new QuotasImpl(projects)

  "Quotas" should {

    "be fetched from configuration" in {
      quotas.fetch(project.ref).accepted shouldEqual Quota(resources = 100)
      quotas.fetch(project2.ref).accepted shouldEqual Quota(resources = 200)
    }

    "failed to be fetched if project does not exist" in {
      val nonExisting = ProjectRef(Label.unsafe("a"), Label.unsafe("b"))
      quotas.fetch(nonExisting).rejectedWith[WrappedProjectRejection]
    }

    "failed to be fetched if quotas config is disabled" in {
      val quotas = new QuotasImpl(projects)(config.copy(enabled = false))
      quotas.fetch(project.ref).rejectedWith[QuotasDisabled]
    }
  }

}
