package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
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
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsEffectSpec, CatsIOValues}
import org.scalatest.CancelAfterFailure

import java.util.UUID

class ElasticSearchViewsRoutesFixtures
    extends CatsEffectSpec
    with RouteHelpers
    with CatsIOValues
    with DoobieScalaTestFixture
    with CirceLiteral
    with CirceEq
    with CancelAfterFailure
    with ConfigFixtures
    with CirceMarshalling
    with Fixtures {

  val uuid: UUID = UUID.randomUUID()

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val aclCheck: AclSimpleCheck = AclSimpleCheck().accepted

  val realm: Label = Label.unsafe("wonderland")

  val reader = User("reader", realm)
  val writer = User("writer", realm)

  implicit private val callerReader: Caller =
    Caller(reader, Set(reader, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val callerWriter: Caller =
    Caller(writer, Set(writer, Anonymous, Authenticated(realm), Group("group", realm)))
  val identities                            = IdentitiesDummy(callerReader, callerWriter)

  val asReader = addCredentials(OAuth2BearerToken("reader"))
  val asWriter = addCredentials(OAuth2BearerToken("writer"))

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
