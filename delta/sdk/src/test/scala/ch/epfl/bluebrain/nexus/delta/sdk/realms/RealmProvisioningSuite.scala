package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmFields
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{RealmNotFound, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

class RealmProvisioningSuite extends NexusSuite with Doobie.Fixture with ConfigFixtures with IOFromMap {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private val provisioning = RealmsProvisioningConfig(enabled = false, Map.empty)
  private val config       = RealmsConfig(eventLogConfig, pagination, provisioning)

  private val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  private lazy val xas = doobie()

  private val github     = Label.unsafe("github")
  private val githubName = Name.unsafe("github-name")

  private val gitlab     = Label.unsafe("gitlab")
  private val gitlabName = Name.unsafe("gitlab-name")

  private val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  private val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  private lazy val realms = RealmsImpl(
    config,
    ioFromMap(
      Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    ),
    xas,
    clock
  )

  test("Provision the different realms according to the configuration") {
    val githubRealm = RealmFields(githubName, githubOpenId, None, None)
    val gitlabRealm = RealmFields(gitlabName, gitlabOpenId, None, None)
    val inactive    = RealmsProvisioningConfig(enabled = false, Map(github -> githubRealm))
    for {
      // Github realm should not be created as provisioning is disabled
      _           <- RealmProvisioning(realms, inactive, serviceAccount)
      _           <- realms.fetch(github).intercept[RealmNotFound]
      githubConfig = RealmsProvisioningConfig(enabled = true, Map(github -> githubRealm))
      // Github realm should be created as provisioning is disabled
      _           <- RealmProvisioning(realms, githubConfig, serviceAccount)
      _           <- realms.fetch(github).map(_.rev).assertEquals(1)
      // Github realm should NOT be updated and the gitlab one should be created
      bothConfig   = RealmsProvisioningConfig(enabled = true, Map(github -> githubRealm, gitlab -> gitlabRealm))
      _           <- RealmProvisioning(realms, bothConfig, serviceAccount)
      _           <- realms.fetch(github).map(_.rev).assertEquals(1)
      _           <- realms.fetch(gitlab).map(_.rev).assertEquals(1)
    } yield ()
  }

  test("Fail for a invalid OpenId config") {
    val invalidRealm  = RealmFields(Name.unsafe("xxx"), uri"https://localhost/xxx", None, None)
    val invalidConfig = RealmsProvisioningConfig(enabled = true, Map(Label.unsafe("xxx") -> invalidRealm))
    RealmProvisioning(realms, invalidConfig, serviceAccount).intercept[UnsuccessfulOpenIdConfigResponse]
  }
}
