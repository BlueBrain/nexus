package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.data.NonEmptySet
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, ProjectContextRejection, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.Type.{ExcludedType, IncludedType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeStep
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import io.circe.{Json, JsonObject}
import monix.bio.UIO
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors, OptionValues}

import java.time.Instant
import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchViewsQuerySpec(override val docker: ElasticSearchDocker)
    extends TestKit(ActorSystem("ElasticSearchViewsQuerySpec"))
    with DoobieScalaTestFixture
    with Matchers
    with EitherValuable
    with CirceLiteral
    with CancelAfterFailure
    with Inspectors
    with ElasticSearchClientSetup
    with OptionValues
    with ConfigFixtures
    with Eventually
    with Fixtures {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val uuidF: UUIDF = UUIDF.random

  private val prefix = "prefix"
  private val page   = FromPagination(0, 100)

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val charlie: Caller        = Caller(User("Charlie", realm), Set(User("Charlie", realm), Group("users", realm)))
  private val anon: Caller           = Caller(Anonymous, Set(Anonymous))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val aclCheck = AclSimpleCheck(
    (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission, permissions.read)),
    (bob.subject, AclAddress.Root, Set(queryPermission, permissions.read)),
    (Anonymous, AclAddress.Project(project2.ref), Set(queryPermission, permissions.read))
  ).accepted

  private val tpe1 = nxv + "Type1"

  private val mappings = jsonObjectContentOf("mapping.json")

  private val indexingView: IndexingElasticSearchViewValue =
    IndexingElasticSearchViewValue(
      resourceTag = None,
      pipeline = List(PipeStep.noConfig(FilterDeprecated.label), PipeStep.noConfig(DiscardMetadata.label)),
      mapping = Some(mappings),
      settings = None,
      permission = queryPermission,
      context = None
    )

  private val defaultView         = ViewRef(project1.ref, defaultViewId)
  private val defaultView2        = ViewRef(project2.ref, defaultViewId)
  private val view1Proj1          = ViewRef(project1.ref, nxv + "view1Proj1")
  private val view2Proj1          = ViewRef(project1.ref, nxv + "view2Proj1")
  private val view1Proj2          = ViewRef(project2.ref, nxv + "view1Proj2")
  private val view2Proj2          = ViewRef(project2.ref, nxv + "view2Proj2")
  private val deprecatedViewProj1 = ViewRef(project1.ref, nxv + "deprecatedViewProj1")

  // Aggregates all views of project1
  private val aggView1Proj1      = ViewRef(project1.ref, nxv + "aggView1Proj1")
  private val aggView1Proj1Views = AggregateElasticSearchViewValue(
    NonEmptySet.of(view1Proj1, view2Proj1)
  )

  // Aggregates view1 of project2, references an aggregated view on project 2 and references the previous aggregate which aggregates all views of project1
  private val aggView1Proj2      = ViewRef(project2.ref, nxv + "aggView1Proj2")
  private val aggView1Proj2Views = AggregateElasticSearchViewValue(
    NonEmptySet.of(view1Proj2, aggView1Proj1)
  )

  // Aggregates view2 of project2 and references aggView1Proj2
  private val aggView2Proj2      = ViewRef(project1.ref, nxv + "aggView1Proj2")
  private val aggView2Proj2Views = AggregateElasticSearchViewValue(
    NonEmptySet.of(view2Proj2, aggView1Proj2)
  )

  private val indexingViews: List[ViewRef] =
    List(defaultView, defaultView2, view1Proj1, view2Proj1, view1Proj2, view2Proj2)

  private def createDocuments(view: ViewRef): UIO[Seq[Json]] =
    (0 until 3).toList.traverse { idx =>
      val resource = ResourceGen.resource(view.viewId / idx.toString, view.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = Set(nxv + idx.toString, tpe1), rev = idx)
        .copy(createdAt = Instant.EPOCH.plusSeconds(idx.toLong))
        .toCompactedJsonLd
        .map(_.json)
    }.hideErrors

  private def extractSources(json: Json) = {
    json.hcursor
      .downField("hits")
      .get[Vector[Json]]("hits")
      .flatMap(seq => seq.traverse(_.hcursor.get[Json]("_source")))
      .rightValue
  }

  "An ElasticSearchViewsQuery" should {

    val fetchContext = FetchContextDummy[ElasticSearchViewRejection](
      List(project1, project2),
      ProjectContextRejection
    )

    lazy val views: ElasticSearchViews = ElasticSearchViews(
      fetchContext,
      ResolverContextResolution(rcr),
      ValidateElasticSearchView(
        registry,
        UIO.pure(Set(queryPermissions)),
        esClient.createIndex(_, _, _).void,
        "prefix",
        10,
        xas
      ),
      eventLogConfig,
      xas
    ).accepted

    lazy val viewsQuery = ElasticSearchViewsQuery(
      aclCheck,
      fetchContext,
      views,
      esClient,
      prefix,
      xas
    )

    "create the indexing views views" in {
      indexingViews.traverse { viewRef =>
        views.create(viewRef.viewId, viewRef.project, indexingView)
      }.accepted
    }

    "create the deprecate view" in {
      views.create(deprecatedViewProj1.viewId, deprecatedViewProj1.project, indexingView) >>
        views.deprecate(deprecatedViewProj1.viewId, deprecatedViewProj1.project, 1)
    }.accepted

    "create the aggregate views" in {
      views.create(aggView1Proj1.viewId, aggView1Proj1.project, aggView1Proj1Views) >>
        views.create(aggView1Proj2.viewId, aggView1Proj2.project, aggView1Proj2Views) >>
        views.create(aggView2Proj2.viewId, aggView2Proj2.project, aggView2Proj2Views)
    }.accepted

    "create the cycle between project2 aggregate views" in {
      val newValue = AggregateElasticSearchViewValue(
        NonEmptySet.of(view1Proj1, view2Proj1, aggView2Proj2)
      )
      views.update(aggView1Proj1.viewId, aggView1Proj1.project, 1, newValue).accepted
    }

    "index documents" in {
      indexingViews
        .foldLeftM(Seq.empty[ElasticSearchBulk]) { case (bulk, ref) =>
          views.fetchIndexingView(ref.viewId, ref.project).flatMap { view =>
            val index = IndexLabel.unsafe(ElasticSearchViews.index(view, prefix))
            createDocuments(ref).map { docs =>
              docs.map(ElasticSearchBulk.Index(index, genString(), _)) ++ bulk
            }
          }
        }
        .flatMap(esClient.bulk(_))
    }.accepted

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
      val expected1 = createDocuments(defaultView).accepted.toSet[Json].map(_.asObject.value)
      val expected2 = createDocuments(defaultView2).accepted.toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result = viewsQuery.list(page, filter, SortList.empty)(bob).accepted
          result.sources.toSet shouldEqual expected1 ++ expected2
        }
        eventually {
          val result = viewsQuery.list(page, filter, SortList.empty)(alice).accepted
          result.sources.toSet shouldEqual expected1
        }
        eventually {
          val result = viewsQuery.list(page, filter, SortList.empty)(anon).accepted
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
      val expected = createDocuments(defaultView).accepted.toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result = viewsQuery.list(project1.ref, page, filter, SortList.empty).accepted
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
      val result = viewsQuery.list(project1.ref, pagination, params, sort).accepted
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
      val expected = createDocuments(defaultView).accepted.toSet[Json].map(_.asObject.value)
      forAll(params) { filter =>
        eventually {
          val result =
            viewsQuery.list(project1.ref, schemas.resources, page, filter, SortList.empty).accepted
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
      val expected = createDocuments(defaultView).accepted.head.asObject.value
      forAll(params) { filter =>
        val result = viewsQuery.list(project1.ref, page, filter, SortList.empty).accepted
        result.sources shouldEqual List(expected)
      }
    }

    "query an indexed view" in eventually {
      val proj   = view1Proj1.project
      val result = viewsQuery.query(view1Proj1.viewId, proj, JsonObject.empty, Query.Empty).accepted
      extractSources(result) shouldEqual createDocuments(view1Proj1).accepted
    }

    "query an indexed view without permissions" in eventually {
      val proj = view1Proj1.project
      viewsQuery
        .query(view1Proj1.viewId, proj, JsonObject.empty, Query.Empty)(anon)
        .rejectedWith[AuthorizationFailed]
    }

    "query a deprecated indexed view without permissions" in eventually {
      val proj = deprecatedViewProj1.project
      viewsQuery
        .query(deprecatedViewProj1.viewId, proj, JsonObject.empty, Query.Empty)
        .rejectedWith[ViewIsDeprecated]
    }

    "query an aggregated view" in eventually {
      val proj   = aggView1Proj2.project
      val result =
        viewsQuery.query(aggView1Proj2.viewId, proj, jobj"""{"size": 100}""", Query.Empty)(bob).accepted

      extractSources(result).toSet shouldEqual indexingViews.drop(2).flatMap(createDocuments(_).accepted).toSet
    }

    "query an aggregated view without permissions in some projects" in {
      val proj   = aggView1Proj2.project
      val result =
        viewsQuery.query(aggView1Proj2.viewId, proj, jobj"""{"size": 100}""", Query.Empty)(alice).accepted
      extractSources(result).toSet shouldEqual List(view1Proj1, view2Proj1).flatMap(createDocuments(_).accepted).toSet
    }

    "get no results if user has access to no projects" in {
      val proj   = aggView1Proj2.project
      val result =
        viewsQuery.query(aggView1Proj2.viewId, proj, jobj"""{"size": 100}""", Query.Empty)(charlie).accepted
      extractSources(result).toSet shouldEqual Set.empty
    }
  }

}
