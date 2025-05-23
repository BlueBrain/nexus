package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.TestMatchers
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsEffectSpec, CatsIOValues}
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

trait CompositeViewsRoutesFixtures
    extends CatsEffectSpec
    with RouteHelpers
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

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri.unsafe("http://localhost", "v1")
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm  = Label.unsafe("myrealm")
  val reader = User("reader", realm)
  val writer = User("writer", realm)

  val identities = IdentitiesDummy.fromUsers(reader, writer)

  val aclCheck = AclSimpleCheck().accepted

}
