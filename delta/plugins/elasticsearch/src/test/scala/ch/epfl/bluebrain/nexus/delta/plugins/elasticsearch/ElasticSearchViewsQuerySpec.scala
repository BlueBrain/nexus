package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewGen._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.index
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery.{FetchDefaultView, FetchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, InvalidElasticSearchViewId, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.Type.{ExcludedType, IncludedType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{permissions => _, _}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{DiscardMetadata, FilterDeprecated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchViewsQuerySpec(override val docker: ElasticSearchDocker)
    extends TestKit(ActorSystem("ElasticSearchViewsQuerySpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ElasticSearchClientSetup
    with ConfigFixtures
    with IOValues
    with Eventually
    with Fixtures {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  private val fixedUuid             = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(fixedUuid)

  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit private val baseUri: BaseUri                       = BaseUri("http://localhost", Label.unsafe("v1"))

  private val page = FromPagination(0, 100)

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val charlie: Caller        = Caller(User("Charlie", realm), Set(User("Charlie", realm), Group("users", realm)))
  private val anon: Caller           = Caller(Anonymous, Set(Anonymous))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val acls = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission, permissions.read)),
      (bob.subject, AclAddress.Root, Set(queryPermission, permissions.read)),
      (Anonymous, AclAddress.Project(project2.ref), Set(queryPermission, permissions.read))
    )
    .accepted

  private val tpe1 = nxv + "Type1"

  private def indexingView(id: Iri, project: ProjectRef): IndexingViewResource =
    resourceFor(
      id,
      project,
      IndexingElasticSearchViewValue(
        resourceTag = None,
        pipeline = List(FilterDeprecated(), DiscardMetadata()),
        mapping = None,
        settings = None,
        permission = permissions.query,
        context = None
      )
    )
      .asInstanceOf[IndexingViewResource]

  private def aggView(id: Iri, project: ProjectRef, refs: (Iri, ProjectRef)*): ResourceF[AggregateElasticSearchView] = {
    val set      = refs.map { case (iri, p) => ViewRef(p, iri) }
    val viewRefs = NonEmptySet.of(set.head, set.tail: _*)
    resourceFor(id, project, AggregateElasticSearchViewValue(viewRefs))
      .asInstanceOf[ResourceF[AggregateElasticSearchView]]
  }

  private val mappings            = jsonObjectContentOf("mapping.json")
  private val defaultView         = indexingView(defaultViewId, project1.ref)
  private val defaultView2        = indexingView(defaultViewId, project2.ref)
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

  private val indexingViews = List(defaultView, defaultView2, view1Proj1, view2Proj1, view1Proj2, view2Proj2)

  private val views: Map[(Iri, ProjectRef), ViewResource] =
    List(
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

  private val fetchDefault: FetchDefaultView = {
    case p if p == project1.ref => UIO.pure(defaultView)
    case p if p == project2.ref => UIO.pure(defaultView2)
    case p                      => IO.raiseError(ViewNotFound(nxv + "other", p))
  }

  private val fetch: FetchView = {
    case (IdSegmentRef.Latest(id: IriSegment), p) =>
      IO.fromEither(views.get(id.value -> p).toRight(ViewNotFound(id.value, p)))
    case (id, _)                                  => IO.raiseError(InvalidElasticSearchViewId(id.value.asString))
  }

  private def createDocuments(view: IndexingViewResource): Seq[Json] =
    (0 until 3).map { idx =>
      val resource = ResourceGen.resource(view.id / idx.toString, view.value.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = Set(nxv + idx.toString, tpe1), rev = idx.toLong)
        .copy(createdAt = Instant.EPOCH.plusSeconds(idx.toLong))
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

    val (_, projects) =
      ProjectSetup
        .init(
          orgsToCreate = List(project1.organizationLabel, project2.organizationLabel),
          projectsToCreate = List(project1, project2)
        )
        .accepted

    val visitor = new ViewRefVisitor(fetch(_, _).map { view =>
      view.value match {
        case v: IndexingElasticSearchView  =>
          IndexedVisitedView(ViewRef(v.project, v.id), v.permission, index(v.uuid, view.rev, externalConfig))
        case v: AggregateElasticSearchView => AggregatedVisitedView(ViewRef(v.project, v.id), v.views)
      }
    })

    lazy val views = new ElasticSearchViewsQueryImpl(
      () => UIO.pure(List(defaultView, defaultView2)),
      fetchDefault,
      fetch,
      visitor,
      acls,
      projects,
      esClient
    )

    "index documents" in {
      val bulkSeq = indexingViews.foldLeft(Seq.empty[ElasticSearchBulk]) { (bulk, v) =>
        val index   = IndexLabel.unsafe(ElasticSearchViews.index(v, externalConfig))
        esClient.createIndex(index, Some(mappings), None).accepted
        val newBulk = createDocuments(v).zipWithIndex.map { case (json, idx) =>
          ElasticSearchBulk.Index(index, idx.toString, json)
        }
        bulk ++ newBulk
      }
      esClient.bulk(bulkSeq).accepted
    }

    "list all resources" in {
      val params    = List(
        ResourcesSearchParams(),
        ResourcesSearchParams(
          schema = Some(Latest(schemas.resources)),
          types = List(IncludedType(tpe1)),
          deprecated = Some(false),
          createdBy = Some(Anonymous),
          updatedBy = Some(Anonymous)
        )
      )
      val expected1 = createDocuments(defaultView).toSet[Json].map(_.asObject.value)
      val expected2 = createDocuments(defaultView2).toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result = views.list(page, filter, SortList.empty)(bob, baseUri).accepted
          result.sources.toSet shouldEqual expected1 ++ expected2
        }
        eventually {
          val result = views.list(page, filter, SortList.empty)(alice, baseUri).accepted
          result.sources.toSet shouldEqual expected1
        }
        eventually {
          val result = views.list(page, filter, SortList.empty)(anon, baseUri).accepted
          result.sources.toSet shouldEqual expected2
        }
      }
    }

    "list all resources on a project" in {
      val params   = List(
        ResourcesSearchParams(),
        ResourcesSearchParams(
          schema = Some(Latest(schemas.resources)),
          types = List(IncludedType(tpe1)),
          deprecated = Some(false),
          createdBy = Some(Anonymous),
          updatedBy = Some(Anonymous)
        )
      )
      val expected = createDocuments(defaultView).toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result = views.list(project1.ref, page, filter, SortList.empty).accepted
          result.sources.toSet shouldEqual expected
        }
      }
    }

    "list resources on a project and sort" in {
      val pagination = FromPagination(0, 1)

      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
        searchResultsJsonLdEncoder(
          ContextValue(Vocabulary.contexts.metadata),
          pagination,
          "http://localhost/v1/some?a=b"
        )

      val sort   = SortList.byCreationDateAndId
      val params = ResourcesSearchParams()
      val result = views.list(project1.ref, pagination, params, sort).accepted
      result.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf("query/list-result.json")
    }

    "list resources for schema resource" in {
      val params   = List(
        ResourcesSearchParams(),
        ResourcesSearchParams(
          types = List(IncludedType(tpe1)),
          deprecated = Some(false),
          createdBy = Some(Anonymous),
          updatedBy = Some(Anonymous)
        )
      )
      val expected = createDocuments(defaultView).toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result =
            views.list(project1.ref, schemas.resources, page, filter, SortList.empty).accepted
          result.sources.toSet shouldEqual expected
        }
      }
    }

    "list some resources" in {
      val params   = List(
        ResourcesSearchParams(id = Some(defaultViewId / "0")),
        ResourcesSearchParams(rev = Some(0)),
        ResourcesSearchParams(types = List(IncludedType(nxv + "0"))),
        ResourcesSearchParams(types = List(ExcludedType(nxv + "1"), ExcludedType(nxv + "2"))),
        ResourcesSearchParams(id = Some(defaultViewId / "0"), rev = Some(0), types = List(IncludedType(nxv + "0")))
      )
      val expected = createDocuments(defaultView).head.asObject.value
      forAll(params) { filter =>
        val result = views.list(project1.ref, page, filter, SortList.empty).accepted
        result.sources shouldEqual List(expected)
      }
    }

    "query an indexed view" in eventually {
      val proj   = view1Proj1.value.project
      val result = views.query(view1Proj1.id, proj, JsonObject.empty, Query.Empty).accepted
      extractSources(result) shouldEqual createDocuments(view1Proj1)
    }

    "query an indexed view without permissions" in eventually {
      val proj = view1Proj1.value.project
      views
        .query(view1Proj1.id, proj, JsonObject.empty, Query.Empty)(anon)
        .rejectedWith[AuthorizationFailed]
    }

    "query a deprecated indexed view without permissions" in eventually {
      val proj = deprecatedViewProj1.value.project
      views
        .query(deprecatedViewProj1.id, proj, JsonObject.empty, Query.Empty)
        .rejectedWith[ViewIsDeprecated]
    }

    "query an aggregated view" in eventually {
      val proj   = aggView1Proj2.value.project
      val result =
        views.query(aggView1Proj2.id, proj, jobj"""{"size": 100}""", Query.Empty)(bob).accepted

      extractSources(result).toSet shouldEqual indexingViews.drop(2).flatMap(createDocuments).toSet
    }

    "query an aggregated view without permissions in some projects" in {
      val proj   = aggView1Proj2.value.project
      val result =
        views.query(aggView1Proj2.id, proj, jobj"""{"size": 100}""", Query.Empty)(alice).accepted
      extractSources(result).toSet shouldEqual List(view1Proj1, view2Proj1).flatMap(createDocuments).toSet
    }

    "get no results if user has access to no projects" in {
      val proj   = aggView1Proj2.value.project
      val result =
        views.query(aggView1Proj2.id, proj, jobj"""{"size": 100}""", Query.Empty)(charlie).accepted
      extractSources(result).toSet shouldEqual Set.empty
    }
  }

}
