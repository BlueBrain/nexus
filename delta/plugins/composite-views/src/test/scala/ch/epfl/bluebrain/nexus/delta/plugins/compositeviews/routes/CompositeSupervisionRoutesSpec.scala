package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.BlazegraphSupervision
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.BlazegraphSupervision.BlazegraphNamespaceTriples
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

class CompositeSupervisionRoutesSpec extends BaseRouteSpec {

  private val supervisor = User("supervisor", realm)

  implicit private val callerSupervisor: Caller =
    Caller(supervisor, Set(supervisor, Anonymous, Authenticated(realm), Group("group", realm)))

  private val asSupervisor = addCredentials(OAuth2BearerToken("supervisor"))

  private val identities = IdentitiesDummy(callerSupervisor)
  private val aclCheck   = AclSimpleCheck(
    (supervisor, AclAddress.Root, Set(supervision.read))
  ).accepted

  private val project = ProjectRef.unsafe("org", "project")
  private val first   = ViewRef(project, nxv + "first")
  private val second  = ViewRef(project, nxv + "second")

  private val blazegraphSupervision = new BlazegraphSupervision {
    override def get: IO[BlazegraphSupervision.BlazegraphNamespaceTriples] = IO.pure(
      BlazegraphNamespaceTriples(
        153L,
        Map(first -> 42L, second   -> 99L),
        Map("kb"  -> 0L, "unknown" -> 12L)
      )
    )
  }

  private val routes = Route.seal(new CompositeSupervisionRoutes(blazegraphSupervision, identities, aclCheck).routes)

  "The composite views supervision endpoint" should {
    "be forbidden without supervision/read permission" in {
      Get("/supervision/composite-views") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "be accessible with supervision/read permission and return expected payload" in {
      val expected =
        json"""
          {
           "total": 153,
           "assigned" : [
             {
               "count" : 42,
               "project" : "org/project",
               "view" : "https://bluebrain.github.io/nexus/vocabulary/first"
             },
             {
               "count" : 99,
               "project" : "org/project",
               "view" : "https://bluebrain.github.io/nexus/vocabulary/second"
             }
           ],
           "unassigned" : [
             {
               "count" : 0,
               "namespace" : "kb"
             },
             {
               "count" : 12,
               "namespace" : "unknown"
             }
           ]
         }"""

      Get("/supervision/composite-views") ~> asSupervisor ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }
  }

}