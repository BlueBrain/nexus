package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import monix.bio.Task

import java.time.Instant

class SupervisionRoutesSpec extends BaseRouteSpec {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)
  private val aclCheck   = AclSimpleCheck().accepted

  private val projectRef = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject"))

  private val metadata     = ProjectionMetadata("module", "name", Some(projectRef), None)
  private val progress     = ProjectionProgress(Offset.start, Instant.EPOCH, 1L, 1L, 1L)
  private val description1 =
    SupervisedDescription(metadata, ExecutionStrategy.PersistentSingleNode, 1, ExecutionStatus.Running, progress)
  private val description2 =
    SupervisedDescription(metadata, ExecutionStrategy.TransientSingleNode, 0, ExecutionStatus.Running, progress)

  private lazy val routes = Route.seal(
    new SupervisionRoutes(
      identities,
      aclCheck,
      Task.delay { List(description1, description2) }
    ).routes
  )

  "The supervision route" should {

    "be forbidden without supervision/read permission" in {
      Get("/v1/supervision") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "be accessible with supervision/read permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(supervision.read)).accepted
      Get("/v1/supervision") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
    }

    "return the correct running projections" in {
      Get("/v1/supervision") ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("supervision/supervision-running-proj-response.json")
      }
    }

  }

}
