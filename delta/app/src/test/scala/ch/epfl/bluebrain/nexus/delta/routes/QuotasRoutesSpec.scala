package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.{QuotasConfig, QuotasImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import monix.bio.UIO

class QuotasRoutesSpec extends BaseRouteSpec {

  private val asAlice = addCredentials(OAuth2BearerToken(alice.subject))
  private val asBob   = addCredentials(OAuth2BearerToken(bob.subject))

  private val project = ProjectRef.unsafe("org", "project")

  private val identities =
    IdentitiesDummy(
      Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm))),
      Caller(bob, Set(bob))
    )

  private val projects: Projects = null

  implicit private val config: QuotasConfig                    = QuotasConfig(Some(5), Some(10), enabled = true, Map.empty)
  implicit private val serviceAccountCfg: ServiceAccountConfig = ServiceAccountConfig(
    ServiceAccount(User("internal", Label.unsafe("sa")))
  )

  private val projectsStatistics: ProjectsStatistics = (_ => UIO.none)

  private val quotas = new QuotasImpl(projectsStatistics)

  private val aclCheck = AclSimpleCheck(
    (Anonymous, AclAddress.Root, Set(Permissions.events.read)),
    (bob, AclAddress.Project(project), Set(Permissions.quotas.read))
  ).accepted

  private lazy val routes = Route.seal(new QuotasRoutes(identities, aclCheck, projects, quotas).routes)

  "The Quotas route" when {

    "fetching quotas" should {

      "succeed" in {
        Get(s"/v1/quotas/org/project") ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            json"""{"@context": "${contexts.quotas}", "@type": "Quota", "resources": 5, "events": 10}"""
        }
      }

      "fail without quotas/read permissions" in {
        Get(s"/v1/quotas/org/project") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }
  }
}
