package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import cats.Semigroup
import cats.data.NonEmptyList
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.Queries.{batchQuery, singleQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeView, CompositeViewSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run.{Main, Rebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeGraphStream, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViewFactory, CompositeViews, CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, rdfs, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{iriStringContextSyntax, jsonOpsSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, Label, ProjectRef, ViewRestriction}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import ch.epfl.bluebrain.nexus.testkit.mu.{JsonAssertions, NexusSuite, TextAssertions}
import fs2.Stream
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import munit.{AnyFixture, CatsEffectSuite}
import munit.catseffect.IOFixture

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt

class SingleCompositeIndexingSuite extends CompositeIndexingSuite(SinkConfig.Single, singleQuery)
class BatchCompositeIndexingSuite  extends CompositeIndexingSuite(SinkConfig.Batch, batchQuery)

trait CompositeIndexingFixture { self: CatsEffectSuite with FixedClock =>

  implicit private val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.never

  val prefix: String = "delta"

  private val queryConfig      = QueryConfig(10, RefreshStrategy.Delay(10.millis))
  val batchConfig: BatchConfig = BatchConfig(2, 50.millis)
  private val compositeConfig  =
    CompositeViewsFixture.config.copy(
      blazegraphBatch = batchConfig,
      elasticsearchBatch = batchConfig
    )

  private def resource(sinkConfig: SinkConfig): Resource[IO, Setup] = {
    (
      Doobie.resource(),
      ElasticSearchClientSetup.resource(),
      BlazegraphClientSetup.resource()
    )
      .parMapN { case (xas, esClient, bgClient) =>
        val compositeRestartStore = new CompositeRestartStore(xas)
        val projections           =
          CompositeProjections(compositeRestartStore, xas, queryConfig, batchConfig, 1.second, clock)
        val spaces                = CompositeSpaces(prefix, esClient, bgClient)
        val sinks                 = CompositeSinks(prefix, esClient, bgClient, compositeConfig.copy(sinkConfig = sinkConfig))
        Setup(esClient, bgClient, projections, spaces, sinks)
      }
  }

  def compositeIndexing(sinkConfig: SinkConfig): IOFixture[Setup] =
    ResourceSuiteLocalFixture("compositeIndexing", resource(sinkConfig))

}

abstract class CompositeIndexingSuite(sinkConfig: SinkConfig, query: SparqlConstructQuery)
    extends NexusSuite
    with CompositeIndexingFixture
    with Fixtures
    with JsonAssertions
    with TextAssertions {

  private val fixture: IOFixture[Setup] = compositeIndexing(sinkConfig)

  override def munitFixtures: Seq[AnyFixture[_]] = Seq(fixture)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 100.millis)

  private lazy val result = fixture()
  import result._

  // Data to index
  private val museId       = iri"http://music.com/muse"
  private val muse         = Band(museId, "Muse", 1994, Set("progressive rock", "alternative rock", "electronica"))
  private val absolutionId = iri"http://music.com/absolution"
  private val absolution   = Album(absolutionId, "Absolution", museId)
  private val blackHolesId = iri"http://music.com/black_holes_and_revelations"
  private val blackHoles   = Album(blackHolesId, "Black Holes & Revelations", museId)

  private val redHotId          = iri"http://music.com/red_hot_chili_peppers"
  private val redHot            = Band(redHotId, "Red Hot Chilli Peppers", 1983, Set("funk rock", "rap rock"))
  private val bloodSugarId      = iri"http://music.com/blood_sugar_sex_magic"
  private val bloodSugar        = Album(bloodSugarId, "Blood_Sugar_Sex_Magic", redHotId)
  private val californicationId = iri"http://music.com/californication"
  private val californication   = Album(californicationId, "Californication", redHotId)
  private val theGatewayId      = iri"http://music.com/the_getaway"
  private val theGateway        = Album(theGatewayId, "The Getaway", redHotId)

  private val project1 = ProjectRef.unsafe("org", "proj")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org", "proj3")

  // Transforming data as elems
  private def elem[A <: Music](project: ProjectRef, value: A, offset: Long, deprecated: Boolean = false, rev: Int = 1)(
      implicit jsonldEncoder: JsonLdEncoder[A]
  ): IO[SuccessElem[GraphResource]] = {
    for {
      graph     <- jsonldEncoder.graph(value)
      entityType = EntityType(value.getClass.getSimpleName)
      resource   = GraphResource(
                     entityType,
                     project,
                     value.id,
                     rev,
                     deprecated,
                     Latest(schemas + "music.json"),
                     Set(value.tpe),
                     graph,
                     Graph
                       .empty(value.id)
                       .add(rdf.tpe, value.tpe)
                       .add(nxv.project.iri, project.toString)
                       .add(rdfs.label, value.label),
                     Json.obj()
                   )
    } yield SuccessElem(
      entityType,
      value.id,
      Some(project),
      Instant.EPOCH,
      Offset.at(offset),
      resource,
      rev
    )
  }

  // Elems for project 1
  private val elems1: ElemStream[GraphResource] =
    Stream.evalSeq(
      List(
        elem(project1, absolution, 1L),
        elem(project1, blackHoles, 2L, deprecated = true),
        elem(project1, muse, 3L)
      ).sequence
    )

  // Elems for project 2
  private val elems2: ElemStream[GraphResource] =
    Stream.evalSeq(
      List(
        elem(project2, californication, 4L),
        elem(project2, bloodSugar, 5L),
        elem(project2, redHot, 6L)
      ).sequence
    )

  // Elems for project 3
  private val elems3: ElemStream[GraphResource] = Stream.eval(elem(project3, theGateway, 7L))

  private val mainCompleted    = Ref.unsafe[IO, Map[ProjectRef, Int]](Map.empty)
  private val rebuildCompleted = Ref.unsafe[IO, Map[ProjectRef, Int]](Map.empty)

  private def resetCompleted = mainCompleted.set(Map.empty) >> rebuildCompleted.set(Map.empty)

  private def increment(map: Ref[IO, Map[ProjectRef, Int]], project: ProjectRef) =
    map.update(_.updatedWith(project)(_.map(_ + 1).orElse(Some(1))))

  private val compositeStream = new CompositeGraphStream {

    private def stream(source: CompositeViewSource, p: ProjectRef): (ProjectRef, ElemStream[GraphResource]) = {
      val project = source match {
        case _: ProjectSource         => p
        case cps: CrossProjectSource  => cps.project
        case rps: RemoteProjectSource => rps.project
      }

      val s = project match {
        case `project1` => elems1
        case `project2` => elems2
        case `project3` => elems3
        case _          => Stream.empty
      }

      project -> s
    }

    override def main(source: CompositeViewSource, project: ProjectRef): Source = {
      val (p, s) = stream(source, project)
      Source(_ => s.onFinalize(increment(mainCompleted, p)) ++ Stream.never[IO])
    }

    override def rebuild(source: CompositeViewSource, project: ProjectRef): Source = {
      val (p, s) = stream(source, project)
      Source(_ => s.onFinalize(increment(rebuildCompleted, p)))
    }

    override def remaining(source: CompositeViewSource, project: ProjectRef): Offset => IO[Option[RemainingElems]] = {
      offset =>
        stream(source, project)._2.compile.last
          .map(_.map { e => RemainingElems(e.offset.value - offset.value, e.instant) })
    }
  }

  private val registry: ReferenceRegistry = {
    val r = new ReferenceRegistry
    r.register(FilterDeprecated)
    r.register(FilterByType)
    r.register(DiscardMetadata)
    r
  }

  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)

  private val source1Id           = iri"https://bbp.epfl.ch/source1"
  private val source2Id           = iri"https://bbp.epfl.ch/source2"
  private val source3Id           = iri"https://bbp.epfl.ch/source3"
  private val projection1Id       = iri"https://bbp.epfl.ch/projection1"
  private val projection2Id       = iri"https://bbp.epfl.ch/projection2"
  private val projectSource       =
    ProjectSource(
      source1Id,
      UUID.randomUUID(),
      ViewRestriction.None,
      ViewRestriction.None,
      None,
      includeDeprecated = false
    )
  private val crossProjectSource  = CrossProjectSource(
    source2Id,
    UUID.randomUUID(),
    ViewRestriction.None,
    ViewRestriction.None,
    None,
    includeDeprecated = false,
    project2,
    Set(bob)
  )
  private val remoteProjectSource = RemoteProjectSource(
    source3Id,
    UUID.randomUUID(),
    ViewRestriction.None,
    ViewRestriction.None,
    None,
    includeDeprecated = false,
    project3,
    Uri("https://bbp.epfl.ch/nexus")
  )

  private val contextJson             = jsonContentOf("indexing/music-context.json")
  private val elasticSearchProjection = ElasticSearchProjection(
    projection1Id,
    UUID.randomUUID(),
    IndexingRev.init,
    query,
    resourceSchemas = ViewRestriction.None,
    resourceTypes = ViewRestriction.restrictedTo(iri"http://music.com/Band"),
    includeMetadata = false,
    includeDeprecated = false,
    includeContext = false,
    permissions.query,
    None,
    jsonObjectContentOf("indexing/mapping.json"),
    None,
    contextJson.topContextValueOrEmpty.asInstanceOf[ContextObject]
  )

  private val blazegraphProjection = SparqlProjection(
    projection2Id,
    UUID.randomUUID(),
    IndexingRev.init,
    query,
    resourceSchemas = ViewRestriction.None,
    resourceTypes = ViewRestriction.None,
    includeMetadata = false,
    includeDeprecated = false,
    permissions.query
  )

  private val noRebuild = CompositeViewFactory.unsafe(
    NonEmptyList.of(projectSource, crossProjectSource, remoteProjectSource),
    NonEmptyList.of(elasticSearchProjection, blazegraphProjection),
    None
  )

  test("Init the namespaces and indices and then delete them") {
    val ref  = ViewRef(ProjectRef.unsafe("org", "proj"), nxv + "id")
    val uuid = UUID.randomUUID()
    val rev  = 2

    val view = ActiveViewDef(ref, uuid, rev, noRebuild)

    val commonNs        = commonNamespace(uuid, noRebuild.sourceIndexingRev, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, prefix)
    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, prefix)

    for {
      // Initialise the namespaces and indices
      _ <- spaces.init(view)
      _ <- bgClient.existsNamespace(commonNs).assertEquals(true)
      _ <- bgClient.existsNamespace(sparqlNamespace).assertEquals(true)
      _ <- esClient.existsIndex(elasticIndex).assertEquals(true)
      // Delete them on destroy
      _ <- spaces.destroyAll(view)
      _ <- bgClient.existsNamespace(commonNs).assertEquals(false)
      _ <- bgClient.existsNamespace(sparqlNamespace).assertEquals(false)
      _ <- esClient.existsIndex(elasticIndex).assertEquals(false)
    } yield ()
  }

  test("Init the namespaces and indices and destroy the projections individually") {
    val ref  = ViewRef(ProjectRef.unsafe("org", "proj"), nxv + "id")
    val uuid = UUID.randomUUID()
    val rev  = 2

    val view = ActiveViewDef(ref, uuid, rev, noRebuild)

    val commonNs        = commonNamespace(uuid, noRebuild.sourceIndexingRev, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, prefix)
    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, prefix)

    for {
      // Initialise the namespaces and indices
      _ <- spaces.init(view)
      _ <- bgClient.existsNamespace(commonNs).assertEquals(true)
      _ <- bgClient.existsNamespace(sparqlNamespace).assertEquals(true)
      _ <- esClient.existsIndex(elasticIndex).assertEquals(true)
      // Delete the blazegraph projection
      _ <- spaces.destroyProjection(view, blazegraphProjection)
      _ <- bgClient.existsNamespace(commonNs).assertEquals(true)
      _ <- bgClient.existsNamespace(sparqlNamespace).assertEquals(false)
      _ <- esClient.existsIndex(elasticIndex).assertEquals(true)
      // Delete the elasticsearch projection
      _ <- spaces.destroyProjection(view, elasticSearchProjection)
      _ <- bgClient.existsNamespace(commonNs).assertEquals(true)
      _ <- bgClient.existsNamespace(sparqlNamespace).assertEquals(false)
      _ <- esClient.existsIndex(elasticIndex).assertEquals(false)
    } yield ()
  }

  private def start(view: ActiveViewDef) = {
    for {
      compiled <- CompositeViewDef.compile(view, sinks, PipeChain.compile(_, registry), compositeStream, projections)
      _        <- spaces.init(view)
      _        <- Projection(compiled, IO.none, _ => IO.unit, _ => IO.unit)(batchConfig)
    } yield compiled
  }

  private val resultMuse: Json     = jsonContentOf("indexing/result_muse.json")
  private val resultRedHot: Json   = jsonContentOf("indexing/result_red_hot.json")
  private val resultMuseMetadata   = jsonContentOf("indexing/result_muse_metadata.json")
  private val resultRedHotMetadata = jsonContentOf("indexing/result_red_hot_metadata.json")

  test("Indexing resources without rebuild") {
    val uuid   = UUID.randomUUID()
    val viewId = iri"https://bbp.epfl.ch/composite"
    val rev    = 1

    val viewRef = ViewRef(project1, viewId)
    val view    = ActiveViewDef(viewRef, uuid, rev, noRebuild)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, prefix)

    val projectionName   = s"composite-views-${view.ref.project}-${view.ref.viewId}-${view.rev}"
    val expectedMetadata =
      ProjectionMetadata(CompositeViews.entityType.value, projectionName, Some(project1), Some(viewId))

    val expectedProgress = CompositeProgress(
      Map(
        CompositeBranch(source1Id, projection1Id, Main) ->
          ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0),
        CompositeBranch(source1Id, projection2Id, Main) ->
          ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0),
        CompositeBranch(source2Id, projection1Id, Main) ->
          ProjectionProgress(Offset.at(6L), Instant.EPOCH, 3, 2, 0),
        CompositeBranch(source2Id, projection2Id, Main) ->
          ProjectionProgress(Offset.at(6L), Instant.EPOCH, 3, 2, 0),
        CompositeBranch(source3Id, projection1Id, Main) ->
          ProjectionProgress(Offset.at(7L), Instant.EPOCH, 1, 1, 0),
        CompositeBranch(source3Id, projection2Id, Main) ->
          ProjectionProgress(Offset.at(7L), Instant.EPOCH, 1, 1, 0)
      )
    )

    for {
      compiled <- start(view)
      _         = assertEquals(compiled.metadata, expectedMetadata)
      _        <- mainCompleted.get.map(_.get(project1)).assertEquals(Some(1)).eventually
      _        <- mainCompleted.get.map(_.get(project2)).assertEquals(Some(1)).eventually
      _        <- mainCompleted.get.map(_.get(project3)).assertEquals(Some(1)).eventually
      _        <- rebuildCompleted.get.assertEquals(Map.empty[ProjectRef, Int])
      _        <- projections.progress(view.indexingRef).assertEquals(expectedProgress).eventually
      _        <- checkElasticSearchDocuments(
                    elasticIndex,
                    resultMuse,
                    resultRedHot
                  ).assert.eventually
      _        <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result.nt"))
    } yield ()
  }

  test("Indexing resources including metadata and deprecated without rebuild") {
    val value = CompositeViewFactory.unsafe(
      NonEmptyList.of(
        projectSource.copy(includeDeprecated = true),
        crossProjectSource.copy(includeDeprecated = true),
        remoteProjectSource.copy(includeDeprecated = true)
      ),
      NonEmptyList.of(
        elasticSearchProjection.copy(includeMetadata = true, includeDeprecated = true),
        blazegraphProjection.copy(includeMetadata = true, includeDeprecated = true)
      ),
      None
    )

    val uuid   = UUID.randomUUID()
    val viewId = iri"https://bbp.epfl.ch/composite2"
    val rev    = 1

    val view = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, value)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, prefix)

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project2)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project3)).assertEquals(Some(1)).eventually
      _ <- rebuildCompleted.get.assertEquals(Map.empty[ProjectRef, Int])
      _ <- checkElasticSearchDocuments(
             elasticIndex,
             resultMuseMetadata,
             resultRedHotMetadata
           ).assert.eventually
      _ <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result_metadata.nt")).assert.eventually
    } yield ()
  }

  test("Indexing resources with interval restart") {
    val uuid = UUID.randomUUID()

    val rebuild = noRebuild.copy(rebuildStrategy = Some(CompositeView.Interval(2.seconds)))

    val viewId  = iri"https://bbp.epfl.ch/composite3"
    val rev     = 1
    val viewRef = ViewRef(project1, viewId)
    val view    = ActiveViewDef(viewRef, uuid, rev, rebuild)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, prefix)

    implicit def mapSemigroup[A, B]: Semigroup[Map[A, B]] = (x: Map[A, B], y: Map[A, B]) => x ++ y

    val expectedProgress = CompositeProgress(
      NonEmptyList.of(Main, Rebuild).reduceMap { run =>
        Map(
          CompositeBranch(source1Id, projection1Id, run) ->
            ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0),
          CompositeBranch(source1Id, projection2Id, run) ->
            ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0),
          CompositeBranch(source2Id, projection1Id, run) ->
            ProjectionProgress(Offset.at(6L), Instant.EPOCH, 3, 2, 0),
          CompositeBranch(source2Id, projection2Id, run) ->
            ProjectionProgress(Offset.at(6L), Instant.EPOCH, 3, 2, 0),
          CompositeBranch(source3Id, projection1Id, run) ->
            ProjectionProgress(Offset.at(7L), Instant.EPOCH, 1, 1, 0),
          CompositeBranch(source3Id, projection2Id, run) ->
            ProjectionProgress(Offset.at(7L), Instant.EPOCH, 1, 1, 0)
        )
      }
    )

    def checkIndexingState =
      for {
        _ <- projections.progress(view.indexingRef).assertEquals(expectedProgress).eventually
        _ <- checkElasticSearchDocuments(
               elasticIndex,
               resultMuse,
               resultRedHot
             ).assert.eventually
        _ <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result.nt")).assert.eventually
      } yield ()

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project2)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project3)).assertEquals(Some(1)).eventually
      _ <- rebuildCompleted.get.map(_.get(project1)).assertEquals(Some(1)).eventually
      _ <- rebuildCompleted.get.map(_.get(project2)).assertEquals(Some(1)).eventually
      _ <- rebuildCompleted.get.map(_.get(project3)).assertEquals(Some(1)).eventually
      _ <- checkIndexingState
      _ <- projections.scheduleFullRestart(viewRef)(Anonymous)
      _ <- mainCompleted.get.map(_.get(project1)).assertEquals(Some(2)).eventually
      _ <- mainCompleted.get.map(_.get(project2)).assertEquals(Some(2)).eventually
      _ <- mainCompleted.get.map(_.get(project3)).assertEquals(Some(2)).eventually
      _ <- rebuildCompleted.get.map(_.get(project1)).assertEquals(Some(2)).eventually
      _ <- rebuildCompleted.get.map(_.get(project2)).assertEquals(Some(2)).eventually
      _ <- rebuildCompleted.get.map(_.get(project3)).assertEquals(Some(2)).eventually
      _ <- checkIndexingState
    } yield ()
  }

  test("Indexing resources with included JSON-LD context") {
    val value = CompositeViewFactory.unsafe(
      NonEmptyList.of(projectSource, crossProjectSource, remoteProjectSource),
      NonEmptyList.of(elasticSearchProjection.copy(includeContext = true)),
      None
    )

    val uuid = UUID.randomUUID()

    val viewId       = iri"https://bbp.epfl.ch/composite4"
    val rev          = 1
    val view         = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, value)
    val elasticIndex = projectionIndex(elasticSearchProjection, uuid, prefix)

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project2)).assertEquals(Some(1)).eventually
      _ <- mainCompleted.get.map(_.get(project3)).assertEquals(Some(1)).eventually
      _ <- rebuildCompleted.get.assertEquals(Map.empty[ProjectRef, Int])
      _ <- checkElasticSearchDocuments(
             elasticIndex,
             resultMuse.deepMerge(contextJson.removeKeys(keywords.id)),
             resultRedHot.deepMerge(contextJson.removeKeys(keywords.id))
           ).assert.eventually
    } yield ()
  }

  private def checkElasticSearchDocuments(index: IndexLabel, expected: Json*): IO[Unit] = {
    val page = FromPagination(0, 5000)
    for {
      _       <- esClient.refresh(index)
      results <- esClient
                   .search(
                     QueryBuilder.empty.withSort(SortList(List(Sort("@id")))).withPage(page),
                     Set(index.value),
                     Query.Empty
                   )
      _        = assertEquals(results.sources.size, expected.size)
      _        = results.sources.zip(expected).foreach { case (obtained, expected) =>
                   obtained.asJson.equalsIgnoreArrayOrder(expected)
                 }
    } yield ()
  }

  private val checkQuery = SparqlConstructQuery.unsafe("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o} ORDER BY ?s")

  private def checkBlazegraphTriples(namespace: String, expected: String) =
    bgClient
      .query(Set(namespace), checkQuery, SparqlNTriples)
      .map(_.value.toString)
      .map(_.equalLinesUnordered(expected))

}

object CompositeIndexingSuite {

  private val ctxIri = ContextValue(iri"http://music.com/context")

  implicit val config: Configuration = Configuration.default.copy(
    transformMemberNames = {
      case "id"  => keywords.id
      case other => other
    }
  )

  final case class Setup(
      esClient: ElasticSearchClient,
      bgClient: BlazegraphClient,
      projections: CompositeProjections,
      spaces: CompositeSpaces,
      sinks: CompositeSinks
  )

  sealed trait Music extends Product with Serializable {
    def id: Iri

    def tpe: Iri

    def label: String
  }

  final case class Band(id: Iri, name: String, start: Int, genre: Set[String]) extends Music {
    override val tpe: Iri      = iri"http://music.com/Band"
    override val label: String = name
  }

  object Band {
    implicit val bandEncoder: Encoder.AsObject[Band]    =
      deriveConfiguredEncoder[Band].mapJsonObject(_.add("@type", "Band".asJson))
    implicit val bandJsonLdEncoder: JsonLdEncoder[Band] =
      JsonLdEncoder.computeFromCirce((b: Band) => b.id, ctxIri)
  }

  final case class Album(id: Iri, title: String, by: Iri) extends Music {
    override val tpe: Iri      = iri"http://music.com/Album"
    override val label: String = title
  }

  object Album {
    implicit val albumEncoder: Encoder.AsObject[Album]    =
      deriveConfiguredEncoder[Album].mapJsonObject(_.add("@type", "Album".asJson))
    implicit val albumJsonLdEncoder: JsonLdEncoder[Album] =
      JsonLdEncoder.computeFromCirce((b: Album) => b.id, ctxIri)
  }

  final case class Metadata(uuid: UUID)

  object Metadata {
    implicit private val encoderMetadata: Encoder.AsObject[Metadata] = deriveEncoder
    implicit val jsonLdEncoderMetadata: JsonLdEncoder[Metadata]      = JsonLdEncoder.computeFromCirce(ctxIri)
  }

}

object Queries {
  val batchQuery: SparqlConstructQuery = SparqlConstructQuery.unsafe(
    """
      |prefix music: <http://music.com/>
      |CONSTRUCT {
      |	?alias       music:name       ?bandName ;
      |					     music:genre      ?bandGenre ;
      |					     music:start      ?bandStartYear ;
      |					     music:album      ?albumId .
      |	?albumId     music:title   	  ?albumTitle .
      |} WHERE {
      | VALUES ?id { {resource_id} } .
      | BIND( IRI(concat(str(?id), '/', 'alias')) AS ?alias ) .
      |
      |	?id          music:name       ?bandName ;
      |					     music:start      ?bandStartYear;
      |					     music:genre      ?bandGenre .
      |	OPTIONAL {
      |		?id        ^music:by 		    ?albumId .
      |		?albumId   music:title   	  ?albumTitle .
      |	}
      |}
      |""".stripMargin
  )

  val singleQuery: SparqlConstructQuery = SparqlConstructQuery.unsafe(
    """
      |prefix music: <http://music.com/>
      |CONSTRUCT {
      |	?id             music:name       ?bandName ;
      |					        music:genre      ?bandGenre ;
      |					        music:start      ?bandStartYear ;
      |					        music:album      ?albumId .
      |	?albumId        music:title   	 ?albumTitle .
      |} WHERE {
      | BIND( {resource_id} AS ?id ) .
      |
      |	?id             music:name       ?bandName ;
      |					        music:start      ?bandStartYear;
      |					        music:genre      ?bandGenre .
      |	OPTIONAL {
      |		?id         	^music:by 		   ?albumId .
      |		?albumId      music:title   	 ?albumTitle .
      |	}
      |}
      |""".stripMargin
  )
}
