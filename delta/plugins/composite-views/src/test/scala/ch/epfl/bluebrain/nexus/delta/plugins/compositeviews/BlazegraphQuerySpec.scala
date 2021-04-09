package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsSetup.initViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.ProjectSourceFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.SparqlProjectionType
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers, TestMatchers}
import io.circe.JsonObject
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphQuerySpec
    extends AbstractDBSpec
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with RemoteContextResolutionFixture
    with IOValues
    with Eventually
    with TestMatchers {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val uuidF: UUIDF = UUIDF.random

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private val httpConfig: HttpClientConfig           = HttpClientConfig(AlwaysGiveUp, HttpClientWorthRetry.never)
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit val baseUri: BaseUri                               = BaseUri("http://localhost", Label.unsafe("v1"))

  private val endpoint = blazegraphHostConfig.endpoint
  private val client   = BlazegraphClient(HttpClient(), endpoint, None)

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private val properties = propertiesOf("/sparql/index.properties")

  private def nTriples(id: String = genString(), label: String = genString(), value: String = genString()) = {
    val json = jsonContentOf("sparql/example.jsonld", "id" -> id, "label" -> label, "value" -> value)
    ExpandedJsonLd(json).accepted.toGraph.flatMap(_.toNTriples).rightValue
  }

  private def namedGraph(ntriples: NTriples): Uri = (ntriples.rootNode.asIri.value / "graph").toUri.rightValue

  private val org       = Label.unsafe("myorg")
  private val project   = ProjectGen.project("myorg", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val (acls, perms) = AclSetup
    .initValuesWithPerms(
      (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
      (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
      (anon.subject, AclAddress.Root, Set(permissions.read))
    )
    .accepted
  private val (orgs, projs) = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted

  private val query = SparqlQuery("SELECT * { ?subject ?predicate ?object }")

  private val blazeProjection1 =
    SparqlProjectionFields(id = Some(nxv + "blaze1"), query.value, permission = permissions.query)
  private val blazeProjection2 =
    SparqlProjectionFields(id = Some(nxv + "blaze2"), query.value, permission = otherPerm)
  private val esProjection     =
    ElasticSearchProjectionFields(
      id = Some(nxv + "es1"),
      "",
      JsonObject.empty,
      ContextObject(JsonObject.empty),
      permission = permissions.query
    )

  private val compositeView = CompositeViewFields(
    NonEmptySet.of(ProjectSourceFields()),
    NonEmptySet.of(blazeProjection1, blazeProjection2, esProjection),
    None
  )

  private val config = externalIndexing

  private val views: CompositeViews = initViews(orgs, projs, perms, acls, Crypto("user", "password")).accepted

  private val viewsQuery = BlazegraphQuery(acls, views, client)

  private val id  = iri"http://localhost/${genString()}"
  private val res = views.create(id, project.ref, compositeView).accepted

  "A BlazegraphQuery" should {

    // projections
    val blazeProjection1        =
      res.value.projections.value.collectFirst { case p: SparqlProjection if p.id == (nxv + "blaze1") => p }.value
    val blazeProjection2        =
      res.value.projections.value.collectFirst { case p: SparqlProjection if p.id == (nxv + "blaze2") => p }.value
    // projection namespaces
    val blazeProjection1Ns      = CompositeViews.namespace(blazeProjection1, res.value, res.rev, config.prefix)
    val blazeProjection2Ns      = CompositeViews.namespace(blazeProjection2, res.value, res.rev, config.prefix)
    val blazeCommonNs           = BlazegraphViews.namespace(res.value.uuid, res.rev, config)
    // projections triples
    val blazeProjection1Triples = nTriples()
    val blazeProjection2Triples = nTriples()
    val blazeCommonTriples      = nTriples()

    "initialize triples on namespaces" in {
      forAll(
        List(
          blazeProjection1Ns -> blazeProjection1Triples,
          blazeProjection2Ns -> blazeProjection2Triples,
          blazeCommonNs      -> blazeCommonTriples
        )
      ) { case (ns, triples) =>
        client.createNamespace(ns, properties).accepted
        client.replace(ns, namedGraph(triples), triples).accepted
      }
    }

    "query the common Blazegraph namespace" in {
      viewsQuery.query(id, project.ref, query).accepted.asGraph.flatMap(_.toNTriples).rightValue.value.trim should
        equalLinesUnordered(blazeCommonTriples.value.trim)
      viewsQuery.query(id, project.ref, query)(alice).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
    }

    "query all the Blazegraph projections' namespaces" in {
      forAll(
        List(alice -> Set(blazeProjection1Triples), bob -> Set(blazeProjection1Triples, blazeProjection2Triples))
      ) { case (caller, expected) =>
        val triples =
          viewsQuery.queryProjections(id, project.ref, query)(caller).accepted.asGraph.flatMap(_.toNTriples).rightValue
        triples.value.trim should equalLinesUnordered(expected.map(_.value.trim).mkString("\n"))
      }
      viewsQuery.queryProjections(id, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a Blazegraph projections' namespace" in {
      val blaze1  = nxv + "blaze1"
      val es      = nxv + "es1"
      val triples =
        viewsQuery.query(id, blaze1, project.ref, query)(bob).accepted.asGraph.flatMap(_.toNTriples).rightValue
      triples.value.trim should equalLinesUnordered(blazeProjection1Triples.value.trim)
      viewsQuery.query(id, blaze1, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, es, project.ref, query)(bob).rejected shouldEqual
        ProjectionNotFound(id, es, project.ref, SparqlProjectionType)
    }
  }

}
