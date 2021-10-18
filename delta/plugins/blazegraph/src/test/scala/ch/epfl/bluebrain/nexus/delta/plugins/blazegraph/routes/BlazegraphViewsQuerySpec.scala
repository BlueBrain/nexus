package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.namespace
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsGen._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.{FetchProject, FetchView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{AuthorizationFailed, InvalidBlazegraphViewId, ViewIsDeprecated, ViewNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import java.time.Instant
import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphViewsQuerySpec
    extends TestKit(ActorSystem("BlazegraphViewsQuerySpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with TestMatchers
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with IOValues
    with Eventually {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private val httpConfig: HttpClientConfig           = HttpClientConfig(AlwaysGiveUp, HttpClientWorthRetry.never, true)
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit val baseUri: BaseUri                               = BaseUri("http://localhost", Label.unsafe("v1"))

  private val endpoint = blazegraphHostConfig.endpoint
  private val client   = BlazegraphClient(HttpClient(), endpoint, None, 10.seconds)

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller           = Caller(Anonymous, Set(Anonymous))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val acls = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission)),
      (bob.subject, AclAddress.Root, Set(queryPermission)),
      (Anonymous, AclAddress.Project(project2.ref), Set(queryPermission))
    )
    .accepted

  private def indexingView(id: Iri, project: ProjectRef): IndexingViewResource =
    resourceFor(id, project, IndexingBlazegraphViewValue()).asInstanceOf[IndexingViewResource]

  private def aggView(id: Iri, project: ProjectRef, refs: (Iri, ProjectRef)*): ResourceF[AggregateBlazegraphView] = {
    val set      = refs.map { case (iri, p) => ViewRef(p, iri) }
    val viewRefs = NonEmptySet(set.head, set.tail.toSet)
    resourceFor(id, project, AggregateBlazegraphViewValue(viewRefs)).asInstanceOf[ResourceF[AggregateBlazegraphView]]
  }

  private val defaultView         = indexingView(defaultViewId, project1.ref)
  private val view1Proj1          = indexingView(nxv + "view1Proj1", project1.ref)
  private val view2Proj1          = indexingView(nxv + "view2Proj1", project1.ref)
  private val view1Proj2          = indexingView(nxv + "view1Proj2", project2.ref)
  private val view2Proj2          = indexingView(nxv + "view2Proj2", project2.ref)
  private val deprecatedViewProj1 = indexingView(nxv + "deprecatedViewProj1", project1.ref).copy(deprecated = true)

  // Aggregates all views of project1
  private val aggView1Proj1 = aggView(
    nxv + "aggView1Proj1",
    project1.ref,
    view1Proj1.id -> view1Proj1.value.project,
    view2Proj1.id -> view2Proj1.value.project
  )
  // Aggregates view1 of project2, references an aggregated view on project 2 and references the previous aggregate which aggregates all views of project1
  private val aggView1Proj2 = aggView(
    nxv + "aggView1Proj2",
    project2.ref,
    view1Proj2.id           -> view1Proj2.value.project,
    (nxv + "aggView2Proj2") -> project2.ref,
    aggView1Proj1.id        -> aggView1Proj1.value.project
  )

  // Aggregates view2 of project2 and references aggView1Proj2
  private val aggView2Proj2 = aggView(
    nxv + "aggView2Proj2",
    project2.ref,
    view2Proj2.id    -> view2Proj2.value.project,
    aggView1Proj2.id -> aggView1Proj2.value.project
  )

  private val indexingViews = List(defaultView, view1Proj1, view2Proj1, view1Proj2, view2Proj2)

  private val views: Map[(Iri, ProjectRef), ViewResource] =
    List(
      defaultView,
      view1Proj1,
      view2Proj1,
      view1Proj2,
      view2Proj2,
      aggView1Proj1,
      aggView1Proj2,
      aggView2Proj2,
      deprecatedViewProj1
    )
      .map(v => ((v.id, v.value.project), v.asInstanceOf[ViewResource]))
      .toMap

  private val fetchView: FetchView = {
    case (IdSegmentRef.Latest(id: IriSegment), p) =>
      IO.fromEither(views.get(id.value -> p).toRight(ViewNotFound(id.value, p)))
    case (id, _)                                  => IO.raiseError(InvalidBlazegraphViewId(id.value.asString))
  }

  private val projects: Map[ProjectRef, Project] = Map(project1.ref -> project1, project2.ref -> project2)

  private val fetchProject: FetchProject = pRef =>
    IO.fromEither(projects.get(pRef).toRight(WrappedProjectRejection(ProjectNotFound(pRef))))

  private def namedGraph(ntriples: NTriples): Uri = ntriples.rootNode.asIri.value.toUri.rightValue

  private def createGraphs(view: IndexingViewResource): Seq[Graph] =
    (0 until 3).map { idx =>
      Graph.empty(view.id / idx.toString).add(nxv.project.iri, view.value.project.toString)
    }

  private def createNTriples(view: IndexingViewResource*): NTriples =
    view.foldLeft(NTriples.empty) { (ntriples, view) =>
      createGraphs(view).foldLeft(ntriples)(_ ++ _.toNTriples.rightValue)
    }

  private def createTriples(view: IndexingViewResource): Seq[NTriples] =
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
    val visitor = new ViewRefVisitor(fetchView(_, _).map { view =>
      view.value match {
        case v: IndexingBlazegraphView  =>
          IndexedVisitedView(ViewRef(v.project, v.id), v.permission, namespace(v.uuid, view.rev, externalConfig))
        case v: AggregateBlazegraphView => AggregatedVisitedView(ViewRef(v.project, v.id), v.views)
      }
    })
    val views   = BlazegraphViewsQuery(fetchView, visitor, fetchProject, acls, client)

    "index triples" in {
      forAll(indexingViews) { v =>
        val index = BlazegraphViews.namespace(v, externalConfig)
        client.createNamespace(index).accepted
        val bulk  = createTriples(v).map { triples =>
          SparqlWriteQuery.replace(namedGraph(triples), triples)
        }
        client.bulk(index, bulk).accepted
      }
    }

    "query an indexed view" in eventually {
      val proj   = view1Proj1.value.project
      val result = views.query(view1Proj1.id, proj, constructQuery, SparqlNTriples).accepted.value
      result.value should equalLinesUnordered(createNTriples(view1Proj1).value)
    }

    "query an indexed view without permissions" in eventually {
      val proj = view1Proj1.value.project
      views.query(view1Proj1.id, proj, constructQuery, SparqlNTriples)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a deprecated indexed view" in eventually {
      val proj = deprecatedViewProj1.value.project
      views.query(deprecatedViewProj1.id, proj, constructQuery, SparqlNTriples).rejectedWith[ViewIsDeprecated]
    }

    "query an aggregated view" in eventually {
      val proj   = aggView1Proj2.value.project
      val result = views.query(aggView1Proj2.id, proj, constructQuery, SparqlNTriples)(bob).accepted.value
      result.value should equalLinesUnordered(createNTriples(indexingViews.drop(1): _*).value)
    }

    "query an aggregated view without permissions in some projects" in {
      val proj   = aggView1Proj2.value.project
      val result = views.query(aggView1Proj2.id, proj, constructQuery, SparqlNTriples)(alice).accepted.value
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

      val defaultIndex = BlazegraphViews.namespace(defaultView, externalConfig)
      client.replace(defaultIndex, resource1Id.toUri.rightValue, resource1Ntriples).accepted
      client.replace(defaultIndex, resource2Id.toUri.rightValue, resource2Ntriples).accepted
      client.replace(defaultIndex, resource3Id.toUri.rightValue, resource3Ntriples).accepted

      views
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
      views
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
      views
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
