package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.TestMatchers
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

trait CompositeViewsRoutesFixtures
    extends RouteHelpers
    with DoobieScalaTestFixture
    with Matchers
    with CatsIOValues
    with CirceLiteral
    with CirceEq
    with FixedClock
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with CompositeViewsFixture
    with Fixtures {

  import akka.actor.typed.scaladsl.adapter._

  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm                   = Label.unsafe("myrealm")
  val bob                     = User("Bob", realm)
  val asBob                   = addCredentials(OAuth2BearerToken("Bob"))
  implicit val caller: Caller = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))

  val identities = IdentitiesDummy(caller)
  val aclCheck   = AclSimpleCheck().accepted

}
