package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import io.circe.syntax.EncoderOps

import java.time.Instant

class SupervisionRoutesSpec extends BaseRouteSpec {

  private val superviser = User("superviser", realm)

  implicit private val callerSuperviser: Caller =
    Caller(superviser, Set(superviser, Anonymous, Authenticated(realm), Group("group", realm)))

  private val asSuperviser = addCredentials(OAuth2BearerToken("superviser"))

  private val identities = IdentitiesDummy(callerSuperviser)
  private val aclCheck   = AclSimpleCheck().accepted

  private val projectRef  = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject"))
  private val projectRef2 = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject2"))

  private val unhealthyProjects = List(projectRef, projectRef2)

  private val metadata     = ProjectionMetadata("module", "name", Some(projectRef), None)
  private val progress     = ProjectionProgress(Offset.start, Instant.EPOCH, 1L, 1L, 1L)
  private val description1 =
    SupervisedDescription(metadata, ExecutionStrategy.PersistentSingleNode, 1, ExecutionStatus.Running, progress)
  private val description2 =
    SupervisedDescription(metadata, ExecutionStrategy.TransientSingleNode, 0, ExecutionStatus.Running, progress)

  private def routesTemplate(unhealthyProjects: List[ProjectRef]) = Route.seal(
    new SupervisionRoutes(
      identities,
      aclCheck,
      IO.pure { List(description1, description2) },
      IO.pure { unhealthyProjects }
    ).routes
  )

  private val routes = routesTemplate(List.empty)

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclCheck.append(AclAddress.Root, superviser -> Set(supervision.read)).accepted
  }

  "The supervision projection endpoint" should {

    "be forbidden without supervision/read permission" in {
      Get("/v1/supervision/projections") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "be accessible with supervision/read permission and return expected payload" in {
      Get("/v1/supervision/projections") ~> asSuperviser ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("supervision/supervision-running-proj-response.json")
      }
    }

  }

  "The supervision projects endpoint" should {

    "be forbidden without supervision/read permission" in {
      Get("/v1/supervision/projects") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "return a successful http code when there are no unhealthy projects" in {
      val routesWithHealthyProjects = routesTemplate(List.empty)
      Get("/v1/supervision/projects") ~> asSuperviser ~> routesWithHealthyProjects ~> check {
        response.status shouldEqual StatusCodes.OK
      }
    }

    "return an error code when there are unhealthy projects" in {
      val routesWithUnhealthyProjects = routesTemplate(unhealthyProjects)
      Get("/v1/supervision/projects") ~> asSuperviser ~> routesWithUnhealthyProjects ~> check {
        response.status shouldEqual StatusCodes.InternalServerError
        response.asJson shouldEqual unhealthyProjects.asJson
      }
    }

  }

}
