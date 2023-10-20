package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptySet
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuerySuite.Sample
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchBulk
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, DifferentElasticSearchViewType, ProjectContextRejection, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions, ElasticSearchViewRejection, ElasticSearchViewType}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.{PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, DataResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json, JsonObject}
import monix.bio.UIO
import munit.{AnyFixture, Location}

import java.time.Instant

class ElasticSearchViewsQuerySuite
    extends BioSuite
    with Doobie.Fixture
    with ElasticSearchClientSetup.Fixture
    with CirceLiteral
    with TestHelpers
    with Fixtures
    with ConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, doobie)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF     = UUIDF.random

  private val prefix = "prefix"

  private lazy val client = esClient()
  private lazy val xas    = doobie()

  private val realm           = Label.unsafe("myrealm")
  private val alice: Caller   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val charlie: Caller = Caller(User("Charlie", realm), Set(User("Charlie", realm), Group("users", realm)))
  private val anon: Caller    = Caller(Anonymous, Set(Anonymous))

  private val project1 = ProjectGen.project("org", "proj")
  private val project2 = ProjectGen.project("org2", "proj2")

  private val queryPermission = Permission.unsafe("views/query")

  private val aclCheck = AclSimpleCheck(
    // Bob has full access
    (bob.subject, AclAddress.Root, Set(queryPermission, permissions.read)),
    // Alice has access to views in project 1
    (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission, permissions.read, permissions.write)),
    // Charlie has access to views in project 2
    (charlie.subject, AclAddress.Project(project2.ref), Set(queryPermission, permissions.read))
  ).runSyncUnsafe()

  private val mappings = jsonObjectContentOf("defaults/default-mapping.json")

  private val indexingValue: IndexingElasticSearchViewValue =
    IndexingElasticSearchViewValue(
      resourceTag = None,
      pipeline = List(PipeStep.noConfig(FilterDeprecated.ref), PipeStep.noConfig(DiscardMetadata.ref)),
      mapping = Some(mappings),
      settings = None,
      permission = queryPermission,
      context = None
    )

  // Indexing views for project 1
  private val defaultView = ViewRef(project1.ref, defaultViewId)
  private val view1Proj1  = ViewRef(project1.ref, nxv + "view1Proj1")
  private val view2Proj1  = ViewRef(project1.ref, nxv + "view2Proj1")

  // Indexing views for project 2
  private val defaultView2 = ViewRef(project2.ref, defaultViewId)
  private val view1Proj2   = ViewRef(project2.ref, nxv + "view1Proj2")
  private val view2Proj2   = ViewRef(project2.ref, nxv + "view2Proj2")

  // Aggregates all views of project1
  private val aggregate1      = ViewRef(project1.ref, nxv + "aggregate1")
  private val aggregate1Views = AggregateElasticSearchViewValue(
    Some("AggregateView1"),
    Some("Aggregate of views from project1"),
    NonEmptySet.of(view1Proj1, view2Proj1)
  )

  // Aggregates:
  // * view1Proj2
  // * references an aggregated view on project 2
  // * references the previous aggregate which aggregates all views of project1
  private val aggregate2      = ViewRef(project2.ref, nxv + "aggregate2")
  private val aggregate2Views = AggregateElasticSearchViewValue(
    Some("AggregateView2"),
    Some("Aggregate view1proj2 and aggregate of project1"),
    NonEmptySet.of(view1Proj2, aggregate1)
  )

  // Aggregates:
  // * view2 of project2
  private val aggregate3      = ViewRef(project1.ref, nxv + "aggregate3")
  private val aggregate3Views = AggregateElasticSearchViewValue(
    Some("AggregateView3"),
    Some("Aggregate view2proj2 and aggregate2"),
    NonEmptySet.of(view2Proj2, aggregate2)
  )

  private val allDefaultViews                 = List(defaultView, defaultView2)
  private val allIndexingViews: List[ViewRef] = allDefaultViews ++ List(view1Proj1, view2Proj1, view1Proj2, view2Proj2)

  // Resources are indexed in every view
  private def epochPlus(plus: Long) = Instant.EPOCH.plusSeconds(plus)
  private val orgType               = nxv + "Organization"
  private val orgSchema             = ResourceRef.Latest(nxv + "org")
  private val bbp                   =
    Sample(
      "bbp",
      Set(orgType),
      2,
      deprecated = false,
      orgSchema,
      createdAt = epochPlus(5L),
      updatedAt = epochPlus(10L),
      createdBy = alice.subject
    )
  private val epfl                  =
    Sample(
      "epfl",
      Set(orgType),
      1,
      deprecated = false,
      orgSchema,
      createdAt = epochPlus(10L),
      updatedAt = epochPlus(10L),
      updatedBy = alice.subject
    )
  private val datasetSchema         = ResourceRef.Latest(nxv + "dataset")
  private val traceTypes            = Set(nxv + "Dataset", nxv + "Trace")
  private val trace                 = Sample(
    "trace",
    traceTypes,
    3,
    deprecated = false,
    datasetSchema,
    createdAt = epochPlus(15L),
    updatedAt = epochPlus(30L)
  )
  private val cellTypes             = Set(nxv + "Dataset", nxv + "Cell")
  private val cell                  =
    Sample(
      "cell",
      cellTypes,
      3,
      deprecated = true,
      datasetSchema,
      createdAt = epochPlus(20L),
      updatedAt = epochPlus(40L),
      createdBy = alice.subject
    )

  private val allResources = List(bbp, epfl, trace, cell)

  private val fetchContext = FetchContextDummy[ElasticSearchViewRejection](
    List(project1, project2),
    ProjectContextRejection
  )

  private lazy val views = ElasticSearchViews(
    fetchContext,
    ResolverContextResolution(rcr),
    ValidateElasticSearchView(
      _ => Right(()),
      UIO.pure(Set(queryPermission)),
      client.createIndex(_, _, _).void,
      prefix,
      10,
      xas
    ),
    eventLogConfig,
    prefix,
    xas
  ).runSyncUnsafe()

  private lazy val viewsQuery = ElasticSearchViewsQuery(
    aclCheck,
    views,
    client,
    prefix,
    xas
  )

  object Ids {

    /**
      * Extract ids from documents from an Elasticsearch search raw response
      */
    def extractAll(json: Json)(implicit loc: Location): Seq[Iri] = {
      for {
        hits    <- json.hcursor.downField("hits").get[Vector[Json]]("hits")
        sources <- hits.traverse(_.hcursor.get[Json]("_source"))
        ids      = extract(sources)
      } yield ids
    }.rightValue

    /**
      * Extract ids from documents from results from [[SearchResults]]
      */
    def extractAll(results: SearchResults[JsonObject])(implicit loc: Location): Seq[Iri] =
      extract(results.sources.map(_.asJson))

    def extract(results: Seq[Json])(implicit loc: Location): Seq[Iri] =
      results.traverse(extract).rightValue

    def extract(json: Json): Decoder.Result[Iri] = json.hcursor.get[Iri]("@id")

  }

  private val noParameters = Query.Empty

  // Match all resources and sort them by created date and date
  private val matchAllSorted                               = jobj"""{ "size": 100, "sort": [{ "_createdAt": "asc" }, { "@id": "asc" }] }"""
//  private val sort                                         = SortList.byCreationDateAndId
  implicit private val defaultSort: Ordering[DataResource] = Ordering.by { r => r.createdAt -> r.id }

  /**
    * Generate ids for the provided samples for the given view and sort them by creation date and id
    */
  private def generateIds(view: ViewRef, resources: List[Sample]): Seq[Iri] =
    resources.map(_.asResourceF(view)).sorted.map(_.id)

  /**
    * Generate ids for the provided samples for the given views and sort them by creation date and id
    */
  private def generateIds(views: List[ViewRef], resources: List[Sample]): Seq[Iri] =
    views.flatMap { view => resources.map(_.asResourceF(view)) }.sorted.map(_.id)

  test("Init views and populate indices") {
    implicit val caller: Caller = alice
    val createIndexingViews     = allIndexingViews.traverse { viewRef =>
      views.create(viewRef.viewId, viewRef.project, indexingValue)
    }
    val populateIndexingViews   = allIndexingViews.traverse { ref =>
      for {
        view <- views.fetchIndexingView(ref.viewId, ref.project)
        bulk <- allResources.traverse { r =>
                  r.asDocument(ref).map { d =>
                    // We create a unique id across all indices
                    ElasticSearchBulk.Index(view.index, genString(), d)
                  }
                }
        _    <- client.bulk(bulk)
        // We refresh explicitly
        _    <- client.refresh(view.index)
      } yield ()
    }

    val createAggregateViews = for {
      _ <- views.create(aggregate1.viewId, aggregate1.project, aggregate1Views)
      _ <- views.create(aggregate2.viewId, aggregate2.project, aggregate2Views)
      _ <- views.create(aggregate3.viewId, aggregate3.project, aggregate3Views)
    } yield ()

    // Create cycle to make sure this case is correctly handled
    val createCycle = {
      val targetedViews = NonEmptySet.of(view1Proj1, view2Proj1, aggregate3)
      val newValue      = AggregateElasticSearchViewValue(targetedViews)
      views.update(aggregate1.viewId, aggregate1.project, 1, newValue)
    }

    (createIndexingViews >> populateIndexingViews >> createAggregateViews >> createCycle).void
      .assert(())
      .runSyncUnsafe()
  }

  test("Query for all documents in a view") {
    implicit val caller: Caller = alice
    val expectedIds             = generateIds(view1Proj1, allResources)
    viewsQuery
      .query(view1Proj1, matchAllSorted, noParameters)
      .map(Ids.extractAll)
      .assert(expectedIds)
  }

  test("Query a view without permissions") {
    implicit val caller: Caller = anon
    viewsQuery.query(view1Proj1, JsonObject.empty, Query.Empty).error(AuthorizationFailed)
  }

  test("Query the deprecated view should raise an deprecation error") {
    implicit val caller: Caller = alice
    val deprecated              = ViewRef(project1.ref, nxv + "deprecated")
    for {
      _ <- views.create(deprecated.viewId, deprecated.project, indexingValue)
      _ <- views.deprecate(deprecated.viewId, deprecated.project, 1)
      _ <- viewsQuery
             .query(deprecated, matchAllSorted, noParameters)
             .error(ViewIsDeprecated(deprecated.viewId))
    } yield ()
  }

  test("Query an aggregate view with a user with full access") {
    implicit val caller: Caller = bob
    val accessibleViews         = List(view1Proj1, view2Proj1, view1Proj2, view2Proj2)
    val expectedIds             = generateIds(accessibleViews, allResources)
    viewsQuery
      .query(aggregate2, matchAllSorted, noParameters)
      .map(Ids.extractAll)
      .assert(expectedIds)
  }

  test("Query an aggregate view with a user with limited access") {
    implicit val caller: Caller = alice
    val accessibleViews         = List(view1Proj1, view2Proj1)
    val expectedIds             = generateIds(accessibleViews, allResources)
    viewsQuery
      .query(aggregate2, matchAllSorted, noParameters)
      .map(Ids.extractAll)
      .assert(expectedIds)
  }

  test("Query an aggregate view with a user with no access") {
    implicit val caller: Caller = anon
    val expectedIds             = List.empty
    viewsQuery
      .query(aggregate2, matchAllSorted, noParameters)
      .map(Ids.extractAll)
      .assert(expectedIds)
  }

  test("Obtaining the mapping without permission should fail") {
    implicit val caller: Caller = anon
    viewsQuery
      .mapping(view1Proj1.viewId, project1.ref)
      .assertError(_ == AuthorizationFailed)
  }

  test("Obtaining the mapping for a view that doesn't exist in the project should fail") {
    implicit val caller: Caller = alice
    viewsQuery
      .mapping(view1Proj2.viewId, project1.ref)
      .assertError(_ == ViewNotFound(view1Proj2.viewId, project1.ref))
  }

  test("Obtaining the mapping on an aggregate view should fail") {
    implicit val caller: Caller = alice
    viewsQuery
      .mapping(aggregate1.viewId, project1.ref)
      .assertError(
        _ == DifferentElasticSearchViewType(
          aggregate1.viewId.toString,
          ElasticSearchViewType.AggregateElasticSearch,
          ElasticSearchViewType.ElasticSearch
        )
      )
  }

  test("Obtaining the mapping with views/write permission should succeed") {
    implicit val caller: Caller = alice
    viewsQuery.mapping(view1Proj1.viewId, project1.ref)
  }
}

object ElasticSearchViewsQuerySuite {

  final private case class Sample(
      suffix: String,
      types: Set[Iri],
      rev: Int,
      deprecated: Boolean,
      schema: ResourceRef,
      createdAt: Instant,
      updatedAt: Instant,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ) {

    def asResourceF(view: ViewRef)(implicit rcr: RemoteContextResolution): DataResource = {
      val resource = ResourceGen.resource(view.viewId / suffix, view.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = types, rev = rev, deprecated = deprecated)
        .copy(
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema
        )
    }

    def asDocument(
        view: ViewRef
    )(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jsonldApi: JsonLdApi): UIO[Json] =
      asResourceF(view).toCompactedJsonLd.map(_.json).hideErrors

  }
}
