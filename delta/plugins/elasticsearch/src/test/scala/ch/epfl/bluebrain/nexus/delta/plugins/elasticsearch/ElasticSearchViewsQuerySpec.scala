package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.data.NonEmptySet
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewGen._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery.{FetchDefaultView, FetchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.AggregateElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, InvalidElasticSearchViewId, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import com.whisk.docker.scalatest.DockerTestKit
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ElasticSearchViewsQuerySpec
    extends TestKit(ActorSystem("ElasticSearchViewsQuerySpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with Inspectors
    with ConfigFixtures
    with IOValues
    with ElasticSearchDocker
    with DockerTestKit
    with Eventually {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                = Scheduler.global
  implicit private val httpConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)

  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit def rcr: RemoteContextResolution                   =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json"),
      contexts.error    -> jsonContentOf("contexts/error.json")
    )

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val endpoint = elasticsearchHost.endpoint
  private val client   = new ElasticSearchClient(HttpClient(), endpoint)
  private val page     = FromPagination(0, 100)

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

  private val tpe1 = nxv + "Type1"

  private def indexingView(id: Iri, project: ProjectRef): IndexingViewResource =
    resourceFor(id, project, IndexingElasticSearchViewValue(mapping = Json.obj())).asInstanceOf[IndexingViewResource]

  private def aggView(id: Iri, project: ProjectRef, refs: (Iri, ProjectRef)*): ResourceF[AggregateElasticSearchView] = {
    val set      = refs.map { case (iri, p) => ViewRef(p, iri) }
    val viewRefs = NonEmptySet.of(set.head, set.tail: _*)
    resourceFor(id, project, AggregateElasticSearchViewValue(viewRefs))
      .asInstanceOf[ResourceF[AggregateElasticSearchView]]
  }

  private val mappings    = jsonContentOf("mapping.json")
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

  private val fetchDefault: FetchDefaultView = {
    case p if p == project1.ref => UIO.pure(defaultView)
    case p                      => IO.raiseError(ViewNotFound(nxv + "other", p))
  }

  private val fetch: FetchView = {
    case (id: IriSegment, p) => IO.fromEither(views.get(id.value -> p).toRight(ViewNotFound(id.value, p)))
    case (id, _)             => IO.raiseError(InvalidElasticSearchViewId(id.asString))
  }

  private def createDocuments(view: IndexingViewResource): Seq[Json] =
    (0 until 3).map { idx =>
      val resource = ResourceGen.resource(view.id / idx.toString, view.value.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = Set(nxv + idx.toString, tpe1), rev = idx.toLong)
        .toCompactedJsonLd
        .accepted
        .json
    }

  private def extractSources(json: Json) = {
    json.hcursor
      .downField("hits")
      .get[Vector[Json]]("hits")
      .flatMap(seq => seq.traverse(_.hcursor.get[Json]("_source")))
      .rightValue
  }

  "An ElasticSearchViewsQuery" should {

    val views = new ElasticSearchViewsQuery(fetchDefault, fetch, acls, client)

    "index documents" in {
      forAll(indexingViews) { v =>
        val index = IndexLabel.unsafe(v.index)
        client.createIndex(index, Some(mappings), None).accepted
        val bulk  = createDocuments(v).zipWithIndex.map { case (json, idx) =>
          ElasticSearchBulk.Index(index, idx.toString, json)
        }
        client.bulk(bulk).accepted
      }
    }

    "list all resources" in {
      val params   = List(
        ResourcesSearchParams(),
        ResourcesSearchParams(
          schema = Some(Latest(schemas.resources)),
          types = List(tpe1),
          deprecated = Some(false),
          createdBy = Some(Anonymous),
          updatedBy = Some(Anonymous)
        )
      )
      val expected = createDocuments(defaultView).toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        val result = views.list(project1.ref, page, filter, Query.Empty, SortList.empty).accepted
        result.sources.toSet shouldEqual expected
      }
    }

    "list some resources" in {
      val params   = List(
        ResourcesSearchParams(id = Some(defaultViewId / "0")),
        ResourcesSearchParams(rev = Some(0)),
        ResourcesSearchParams(types = List(nxv + "0")),
        ResourcesSearchParams(id = Some(defaultViewId / "0"), rev = Some(0), types = List(nxv + "0"))
      )
      val expected = createDocuments(defaultView).head.asObject.value
      forAll(params) { filter =>
        val result = views.list(project1.ref, page, filter, Query.Empty, SortList.empty).accepted
        result.sources shouldEqual List(expected)
      }
    }

    "query a indexed view" in {
      val id     = IriSegment(view1Proj1.id)
      val proj   = view1Proj1.value.project
      val result = views.query(id, proj, page, JsonObject.empty, Query.Empty, SortList.empty).accepted
      extractSources(result) shouldEqual createDocuments(view1Proj1)
    }

    "query a indexed view without permissions" in {
      val id   = IriSegment(view1Proj1.id)
      val proj = view1Proj1.value.project
      views.query(id, proj, page, JsonObject.empty, Query.Empty, SortList.empty)(anon).rejectedWith[AuthorizationFailed]
    }

    "query an aggregated view" in {
      val id     = IriSegment(aggView1Proj2.id)
      val proj   = aggView1Proj2.value.project
      val result = views.query(id, proj, page, JsonObject.empty, Query.Empty, SortList.empty)(bob).accepted
      extractSources(result).toSet shouldEqual indexingViews.drop(1).flatMap(createDocuments).toSet
    }

    "query an aggregated view without permissions in some projects" in {
      val id     = IriSegment(aggView1Proj2.id)
      val proj   = aggView1Proj2.value.project
      val result = views.query(id, proj, page, JsonObject.empty, Query.Empty, SortList.empty)(alice).accepted
      extractSources(result).toSet shouldEqual List(view1Proj1, view2Proj1).flatMap(createDocuments).toSet
    }
  }

}
