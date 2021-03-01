package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsGen._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.FetchView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery, SparqlResults, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.AggregateBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{AuthorizationFailed, InvalidBlazegraphViewId, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, IndexingViewResource, ViewRef, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphViewsQuerySpec
    extends TestKit(ActorSystem("BlazegraphViewsQuerySpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with IOValues
    with Eventually {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private val httpConfig: HttpClientConfig           = HttpClientConfig(AlwaysGiveUp, HttpClientWorthRetry.never)
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing

  private val endpoint = blazegraphHostConfig.endpoint
  private val client   = BlazegraphClient(HttpClient(), endpoint, None)

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

  private val defaultView = indexingView(defaultViewId, project1.ref)
  private val view1Proj1  = indexingView(nxv + "view1Proj1", project1.ref)
  private val view2Proj1  = indexingView(nxv + "view2Proj1", project1.ref)
  private val view1Proj2  = indexingView(nxv + "view1Proj2", project2.ref)
  private val view2Proj2  = indexingView(nxv + "view2Proj2", project2.ref)

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
    List(view1Proj1, view2Proj1, view1Proj2, view2Proj2, aggView1Proj1, aggView1Proj2, aggView2Proj2)
      .map(v => ((v.id, v.value.project), v.asInstanceOf[ViewResource]))
      .toMap

  private val fetch: FetchView = {
    case (id: IriSegment, p) => IO.fromEither(views.get(id.value -> p).toRight(ViewNotFound(id.value, p)))
    case (id, _)             => IO.raiseError(InvalidBlazegraphViewId(id.asString))
  }

  private def namedGraph(ntriples: NTriples): Uri = (ntriples.rootNode.asIri.value / "graph").toUri.rightValue

  private def createGraphs(view: IndexingViewResource): Seq[Graph] =
    (0 until 3).map { idx =>
      Graph.empty(view.id / idx.toString).add(nxv.project.iri, view.value.project.toString)
    }

  private def createTriples(view: IndexingViewResource): Seq[NTriples] =
    createGraphs(view).map(_.toNTriples.rightValue)

  private def createRawTriples(view: IndexingViewResource): Set[(String, String, String)] = {
    val graphs: Set[Graph] = createGraphs(view).toSet
    graphs.flatMap(_.triples.map { case (s, p, o) => ((s.toString, p.toString, o.toString)) })
  }

  private def extractRawTriples(result: SparqlResults): Set[(String, String, String)] =
    result.results.bindings.map { triples => (triples("s").value, triples("p").value, triples("o").value) }.toSet

  private val selectAllQuery = SparqlQuery("SELECT * { ?s ?p ?o }")

  "A BlazegraphViewsQuery" should {

    val views      = new BlazegraphViewsQueryImpl(fetch, acls, client)
    val properties = propertiesOf("/sparql/index.properties")

    "index triples" in {
      forAll(indexingViews) { v =>
        val index = v.index
        client.createNamespace(index, properties).accepted
        val bulk  = createTriples(v).map { triples =>
          SparqlWriteQuery.replace(namedGraph(triples), triples)
        }
        client.bulk(index, bulk).accepted
      }
    }

    "query an indexed view" in eventually {
      val proj   = view1Proj1.value.project
      val result = views.query(view1Proj1.id, proj, selectAllQuery).accepted
      extractRawTriples(result) shouldEqual createRawTriples(view1Proj1)
    }

    "query an indexed view without permissions" in eventually {
      val proj = view1Proj1.value.project
      views.query(view1Proj1.id, proj, selectAllQuery)(anon).rejectedWith[AuthorizationFailed]
    }

    "query an aggregated view" in eventually {
      val proj   = aggView1Proj2.value.project
      val result = views.query(aggView1Proj2.id, proj, selectAllQuery)(bob).accepted
      extractRawTriples(result) shouldEqual indexingViews.drop(1).flatMap(createRawTriples).toSet
    }

    "query an aggregated view without permissions in some projects" in {
      val proj   = aggView1Proj2.value.project
      val result = views.query(aggView1Proj2.id, proj, selectAllQuery)(alice).accepted
      extractRawTriples(result) shouldEqual List(view1Proj1, view2Proj1).flatMap(createRawTriples).toSet
    }
  }

}
