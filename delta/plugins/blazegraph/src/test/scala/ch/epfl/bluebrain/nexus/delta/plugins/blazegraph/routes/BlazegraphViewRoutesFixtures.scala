package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{schema, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral}
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, Inspectors}

import java.time.Instant
import java.util.UUID

trait BlazegraphViewRoutesFixtures
    extends RouteHelpers
    with DoobieScalaTestFixture
    with CirceLiteral
    with CirceEq
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with BeforeAndAfterAll
    with Fixtures {

  import akka.actor.typed.scaladsl.adapter._

  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped
  implicit val baseUri: BaseUri                  = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit val ordering: JsonKeyOrdering          =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  implicit val paginationConfig: PaginationConfig = pagination

  val uuid = UUID.randomUUID()

  val aclCheck = AclSimpleCheck().accepted

  val realm                   = Label.unsafe("myrealm")

  val reader = User("reader", realm)
  val writer = User("writer", realm)

  implicit private val callerReader: Caller =
    Caller(reader, Set(reader, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val callerWriter: Caller =
    Caller(writer, Set(writer, Anonymous, Authenticated(realm), Group("group", realm)))
  val identities                            = IdentitiesDummy(callerReader, callerWriter)

  val asReader = addCredentials(OAuth2BearerToken("reader"))
  val asWriter = addCredentials(OAuth2BearerToken("writer"))

  val org           = Label.unsafe("org")
  val orgDeprecated = Label.unsafe("org-deprecated")
  val base          = nxv.base
  val mappings      = ApiMappings("example" -> iri"http://example.com/", "view" -> schema.iri)

  val project                  = ProjectGen.project("org", "proj", base = base, mappings = mappings)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
  val projectRef               = project.ref

  val linksResults: SearchResults[SparqlLink] = UnscoredSearchResults(
    2,
    List(
      UnscoredResultEntry(
        SparqlResourceLink(
          ResourceF(
            iri"http://example.com/id1",
            ResourceUris.resource(
              projectRef,
              projectRef,
              iri"http://example.com/id1"
            ),
            1,
            Set(iri"http://example.com/type1", iri"http://example.com/type2"),
            false,
            Instant.EPOCH,
            Identity.Anonymous,
            Instant.EPOCH,
            Identity.Anonymous,
            ResourceRef(iri"http://example.com/someSchema"),
            List(iri"http://example.com/property1", iri"http://example.com/property2")
          )
        )
      ),
      UnscoredResultEntry(
        SparqlExternalLink(
          iri"http://example.com/external",
          List(iri"http://example.com/property3", iri"http://example.com/property4"),
          Set(iri"http://example.com/type3", iri"http://example.com/type4")
        )
      )
    )
  )
}
