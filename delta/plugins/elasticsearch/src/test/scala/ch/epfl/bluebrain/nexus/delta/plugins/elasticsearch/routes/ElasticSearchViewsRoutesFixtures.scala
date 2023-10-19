package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.identities.{Identities, IdentitiesDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, ProjectResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.ce.{CatsEffectScalaTestAssertions, CatsRunContext}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.util.UUID

class ElasticSearchViewsRoutesFixtures
    extends RouteHelpers
    with DoobieScalaTestFixture
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with CatsRunContext
    with CatsEffectScalaTestAssertions
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with TestHelpers
    with CirceMarshalling
    with Fixtures {

  import akka.actor.typed.scaladsl.adapter._

  val uuid: UUID = UUID.randomUUID()

  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val s: Scheduler                       = Scheduler.global
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val aclCheck: AclSimpleCheck = AclSimpleCheck().accepted

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)

  val caller: Caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  val identities: Identities = IdentitiesDummy(caller)

  val asAlice = addCredentials(OAuth2BearerToken("alice"))

  val project: ProjectResource = ProjectGen.resourceFor(
    ProjectGen.project(
      "myorg",
      "myproject",
      uuid = uuid,
      orgUuid = uuid,
      mappings = ApiMappings("view" -> elasticSearchSchema.iri)
    )
  )
  val projectRef: ProjectRef   = project.value.ref
}
