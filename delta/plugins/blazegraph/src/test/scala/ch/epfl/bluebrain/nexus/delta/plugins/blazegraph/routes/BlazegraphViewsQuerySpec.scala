package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import cats.data.NonEmptySet
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{AuthorizationFailed, ProjectContextRejection, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphDocker
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors, OptionValues}

import java.time.Instant
import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphViewsQuerySpec(docker: BlazegraphDocker)
    extends TestKit(ActorSystem("BlazegraphViewsQuerySpec"))
    with DoobieScalaTestFixture
    with Matchers
    with EitherValuable
    with OptionValues
    with CirceLiteral
    with TestHelpers
    with TestMatchers
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with IOValues
    with Fixtures
    with Eventually {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                = Scheduler.global
  implicit private val httpConfig: HttpClientConfig = HttpClientConfig(AlwaysGiveUp, HttpClientWorthRetry.never, true)
  implicit private val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val uuidF: UUIDF = UUIDF.random

  private lazy val endpoint = docker.hostConfig.endpoint
  private lazy val client   = BlazegraphClient(HttpClient(), endpoint, None, 10.seconds)

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller           = Caller(Anonymous, Set(Anonymous))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val defaultView         = ViewRef(project1.ref, defaultViewId)
  private val view1Proj1          = ViewRef(project1.ref, nxv + "view1Proj1")
  private val view2Proj1          = ViewRef(project1.ref, nxv + "view2Proj1")
  private val view1Proj2          = ViewRef(project2.ref, nxv + "view1Proj2")
  private val view2Proj2          = ViewRef(project2.ref, nxv + "view2Proj2")
  private val deprecatedViewProj1 = ViewRef(project1.ref, nxv + "deprecatedViewProj1")

  // Aggregates all views of project1
  private val aggView1Proj1      = ViewRef(project1.ref, nxv + "aggView1Proj1")
  private val aggView1Proj1Views = AggregateBlazegraphViewValue(
    NonEmptySet.of(view1Proj1, view2Proj1)
  )

  // Aggregates view1 of project2, references an aggregated view on project 2 and references the previous aggregate which aggregates all views of project1
  private val aggView1Proj2      = ViewRef(project2.ref, nxv + "aggView1Proj2")
  private val aggView1Proj2Views = AggregateBlazegraphViewValue(
    NonEmptySet.of(view1Proj2, aggView1Proj1)
  )

  // Aggregates view2 of project2 and references aggView1Proj2
  private val aggView2Proj2      = ViewRef(project2.ref, nxv + "aggView2Proj2")
  private val aggView2Proj2Views = AggregateBlazegraphViewValue(
    NonEmptySet.of(view2Proj2, aggView1Proj2)
  )

  private val indexingViews = List(defaultView, view1Proj1, view2Proj1, view1Proj2, view2Proj2)

  private val fetchContext = FetchContextDummy[BlazegraphViewRejection](
    List(project1, project2),
    ProjectContextRejection
  )

  private def namedGraph(ntriples: NTriples): Uri = ntriples.rootNode.asIri.value.toUri.rightValue

  private def createGraphs(view: ViewRef): Seq[Graph] =
    (0 until 3).map { idx =>
      Graph.empty(view.viewId / idx.toString).add(nxv.project.iri, view.project.toString)
    }

  private def createNTriples(view: ViewRef*): NTriples =
    view.foldLeft(NTriples.empty) { (ntriples, view) =>
      createGraphs(view).foldLeft(ntriples)(_ ++ _.toNTriples.rightValue)
    }

  private def createTriples(view: ViewRef): Seq[NTriples] =
    createGraphs(view).map(_.toNTriples.rightValue)

  private def sparqlResourceLinkFor(resourceId: Iri, path: Iri) =
    SparqlResourceLink(
      ResourceF(
        resourceId,
        ResourceUris.resource(project1.ref, project1.ref, resourceId, ResourceRef(resourceId / "schema"))(
          project1.apiMappings,
          project1.base
        ),
        2,
        Set(resourceId / "type"),
        deprecated = false,
        Instant.EPOCH,
        Identity.Anonymous,
        Instant.EPOCH,
        Identity.Anonymous,
        ResourceRef(resourceId / "schema"),
        List(path)
      )
    )

  private val constructQuery = SparqlConstructQuery("CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }").rightValue

  "A BlazegraphViewsQuery" should {
    lazy val views = BlazegraphViews(
      fetchContext,
      ResolverContextResolution(rcr),
      alwaysValidate,
      client,
      eventLogConfig,
      "prefix",
      xas
    ).accepted

    lazy val viewsQuery = AclSimpleCheck(
      (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission)),
      (bob.subject, AclAddress.Root, Set(queryPermission)),
      (Anonymous, AclAddress.Project(project2.ref), Set(queryPermission))
    ).flatMap { acls => BlazegraphViewsQuery(acls, fetchContext, views, client, "prefix", xas) }.accepted

    "create the indexing views" in {
      indexingViews.traverse { v =>
        views.create(v.viewId, v.project, IndexingBlazegraphViewValue())
      }.accepted
    }

    "create the deprecate view" in {
      views.create(deprecatedViewProj1.viewId, deprecatedViewProj1.project, IndexingBlazegraphViewValue()) >>
        views.deprecate(deprecatedViewProj1.viewId, deprecatedViewProj1.project, 1)
    }.accepted

    "create the aggregate views" in {
      views.create(aggView1Proj1.viewId, aggView1Proj1.project, aggView1Proj1Views) >>
        views.create(aggView1Proj2.viewId, aggView1Proj2.project, aggView1Proj2Views) >>
        views.create(aggView2Proj2.viewId, aggView2Proj2.project, aggView2Proj2Views)
    }.accepted

    "create the cycle between project2 aggregate views" in {
      val newValue = AggregateBlazegraphViewValue(
        NonEmptySet.of(view1Proj1, view2Proj1, aggView2Proj2)
      )
      views.update(aggView1Proj1.viewId, aggView1Proj1.project, 1, newValue).accepted
    }

    "index triples" in {
      indexingViews.traverse { ref =>
        views.fetchIndexingView(ref.viewId, ref.project).flatMap { view =>
          val bulk = createTriples(ref).map { triples =>
            SparqlWriteQuery.replace(namedGraph(triples), triples)
          }
          client.bulk(BlazegraphViews.namespace(view, "prefix"), bulk)
        }
      }.accepted
    }

    "query an indexed view" in eventually {
      val proj   = view1Proj1.project
      val result = viewsQuery.query(view1Proj1.viewId, proj, constructQuery, SparqlNTriples).accepted.value
      result.value should equalLinesUnordered(createNTriples(view1Proj1).value)
    }

    "query an indexed view without permissions" in eventually {
      val proj = view1Proj1.project
      viewsQuery.query(view1Proj1.viewId, proj, constructQuery, SparqlNTriples)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a deprecated indexed view" in eventually {
      val proj = deprecatedViewProj1.project
      viewsQuery.query(deprecatedViewProj1.viewId, proj, constructQuery, SparqlNTriples).rejectedWith[ViewIsDeprecated]
    }

    "query an aggregated view" in eventually {
      val proj   = aggView1Proj2.project
      val result = viewsQuery.query(aggView1Proj2.viewId, proj, constructQuery, SparqlNTriples)(bob).accepted.value
      result.value should equalLinesUnordered(createNTriples(indexingViews.drop(1): _*).value)
    }

    "query an aggregated view without permissions in some projects" in {
      val proj   = aggView1Proj2.project
      val result = viewsQuery.query(aggView1Proj2.viewId, proj, constructQuery, SparqlNTriples)(alice).accepted.value
      result.value should equalLinesUnordered(createNTriples(view1Proj1, view2Proj1).value)
    }

    val resource1Id = iri"http://example.com/resource1"
    val resource2Id = iri"http://example.com/resource2"
    val resource3Id = iri"http://example.com/resource3"
    val resource4Id = iri"http://example.com/resource4"

    "query incoming links" in {
      val resource1Ntriples = NTriples(contentOf("sparql/resource1.ntriples"), resource1Id)
      val resource2Ntriples = NTriples(contentOf("sparql/resource2.ntriples"), resource2Id)
      val resource3Ntriples = NTriples(contentOf("sparql/resource3.ntriples"), resource3Id)

      {
        for {
          defaultIndex <- views.fetchIndexingView(defaultView.viewId, defaultView.project).map { v =>
                            BlazegraphViews.namespace(v, "prefix")
                          }
          _            <- client.replace(defaultIndex, resource1Id.toUri.rightValue, resource1Ntriples)
          _            <- client.replace(defaultIndex, resource2Id.toUri.rightValue, resource2Ntriples)
          _            <- client.replace(defaultIndex, resource3Id.toUri.rightValue, resource3Ntriples)
        } yield ()
      }.accepted

      viewsQuery
        .incoming(resource1Id, project1.ref, Pagination.OnePage)
        .accepted shouldEqual UnscoredSearchResults(
        2,
        Seq(
          UnscoredResultEntry(sparqlResourceLinkFor(resource3Id, iri"http://example.com/incoming")),
          UnscoredResultEntry(sparqlResourceLinkFor(resource2Id, iri"http://example.com/incoming"))
        )
      )
    }

    "query outgoing links" in {
      viewsQuery
        .outgoing(resource1Id, project1.ref, Pagination.OnePage, includeExternalLinks = true)
        .accepted shouldEqual UnscoredSearchResults[SparqlLink](
        3,
        Seq(
          UnscoredResultEntry(sparqlResourceLinkFor(resource3Id, iri"http://example.com/outgoing")),
          UnscoredResultEntry(sparqlResourceLinkFor(resource2Id, iri"http://example.com/outgoing")),
          UnscoredResultEntry(SparqlExternalLink(resource4Id, List(iri"http://example.com/outgoing")))
        )
      )
    }

    "query outgoing links excluding external" in {
      viewsQuery
        .outgoing(resource1Id, project1.ref, Pagination.OnePage, includeExternalLinks = false)
        .accepted shouldEqual UnscoredSearchResults[SparqlLink](
        2,
        Seq(
          UnscoredResultEntry(sparqlResourceLinkFor(resource3Id, iri"http://example.com/outgoing")),
          UnscoredResultEntry(sparqlResourceLinkFor(resource2Id, iri"http://example.com/outgoing"))
        )
      )
    }
  }

}
