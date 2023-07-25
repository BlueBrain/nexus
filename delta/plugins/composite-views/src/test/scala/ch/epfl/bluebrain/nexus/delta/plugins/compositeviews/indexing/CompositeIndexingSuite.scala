package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Resource
import cats.effect.concurrent.Ref
import cats.kernel.Semigroup
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingSuite.{batchConfig, Album, Band, Music}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeView, CompositeViewSource, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run.{Main, Rebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeGraphStream, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, rdfs, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, JsonAssertions, PatienceConfig, ResourceFixture, TextAssertions}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, TestHelpers}
import fs2.Stream
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import munit.AnyFixture

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class CompositeIndexingSuite
    extends BioSuite
    with CompositeIndexingSuite.Fixture
    with TestHelpers
    with Fixtures
    with JsonAssertions
    with TextAssertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(compositeIndexing)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 100.millis)

  private val prefix                                                = "delta"
  private lazy val (esClient, bgClient, projections, spacesBuilder) = compositeIndexing()

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

  private val query = SparqlConstructQuery.unsafe(
    """
      |prefix music: <http://music.com/>
      |CONSTRUCT {
      |	?id             music:name       ?bandName ;
      |					music:genre      ?bandGenre ;
      |					music:start      ?bandStartYear ;
      |					music:album      ?albumId .
      |	?albumId        music:title   	 ?albumTitle .
      |} WHERE {
      | VALUES ?id { {resource_id} }
      |	?id             music:name       ?bandName ;
      |					music:start      ?bandStartYear;
      |					music:genre      ?bandGenre .
      |	OPTIONAL {
      |		?id         	^music:by 		?albumId .
      |		?albumId        music:title   	?albumTitle .
      |	}
      |}
      |""".stripMargin
  )

  private val project1 = ProjectRef.unsafe("org", "proj")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org", "proj3")

  // Transforming data as elems
  private def elem[A <: Music](project: ProjectRef, value: A, offset: Long, deprecated: Boolean = false, rev: Int = 1)(
      implicit jsonldEncoder: JsonLdEncoder[A]
  ) = {
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

  private val mainCompleted    = Ref.unsafe[Task, Map[ProjectRef, Int]](Map.empty)
  private val rebuildCompleted = Ref.unsafe[Task, Map[ProjectRef, Int]](Map.empty)

  private def resetCompleted                                                       = mainCompleted.set(Map.empty) >> rebuildCompleted.set(Map.empty)
  private def increment(map: Ref[Task, Map[ProjectRef, Int]], project: ProjectRef) =
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
      Source(_ => s.onFinalize(increment(mainCompleted, p)) ++ Stream.never[Task])
    }

    override def rebuild(source: CompositeViewSource, project: ProjectRef): Source = {
      val (p, s) = stream(source, project)
      Source(_ => s.onFinalize(increment(rebuildCompleted, p)))
    }

    override def remaining(source: CompositeViewSource, project: ProjectRef): Offset => UIO[Option[RemainingElems]] = {
      offset =>
        stream(source, project)._2.compile.last
          .map(_.map { e => RemainingElems(e.offset.value - offset.value, e.instant) })
          .hideErrors
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
    ProjectSource(source1Id, UUID.randomUUID(), Set.empty, Set.empty, None, includeDeprecated = false)
  private val crossProjectSource  = CrossProjectSource(
    source2Id,
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    includeDeprecated = false,
    project2,
    Set(bob)
  )
  private val remoteProjectSource = RemoteProjectSource(
    source3Id,
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    includeDeprecated = false,
    project3,
    Uri("https://bbp.epfl.ch/nexus"),
    None
  )

  private val contextJson             = jsonContentOf("indexing/music-context.json")
  private val elasticSearchProjection = ElasticSearchProjection(
    projection1Id,
    UUID.randomUUID(),
    query,
    resourceSchemas = Set.empty,
    resourceTypes = Set(iri"http://music.com/Band"),
    resourceTag = None,
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
    query,
    resourceSchemas = Set.empty,
    resourceTypes = Set.empty,
    resourceTag = None,
    includeMetadata = false,
    includeDeprecated = false,
    permissions.query
  )

  private val noRebuild = CompositeViewValue(
    NonEmptySet.of(projectSource, crossProjectSource, remoteProjectSource),
    NonEmptySet.of(elasticSearchProjection, blazegraphProjection),
    None
  )

  test("Init the namespaces and indices and then delete them") {
    val ref  = ViewRef(ProjectRef.unsafe("org", "proj"), nxv + "id")
    val uuid = UUID.randomUUID()
    val rev  = 2

    val view = ActiveViewDef(ref, uuid, rev, noRebuild)

    val spaces          = spacesBuilder(view)
    val commonNamespace = s"${prefix}_${uuid}_$rev"
    val sparqlNamespace = s"${prefix}_${uuid}_${blazegraphProjection.uuid}_$rev"
    val elasticIndex    = IndexLabel.unsafe(s"${prefix}_${uuid}_${elasticSearchProjection.uuid}_$rev")

    for {
      // Initialise the namespaces and indices
      _ <- spaces.init
      _ <- bgClient.existsNamespace(commonNamespace).assert(true)
      _ <- bgClient.existsNamespace(sparqlNamespace).assert(true)
      _ <- esClient.existsIndex(elasticIndex).assert(true)
      // Delete them on destroy
      _ <- spaces.destroy
      _ <- bgClient.existsNamespace(commonNamespace).assert(false)
      _ <- bgClient.existsNamespace(sparqlNamespace).assert(false)
      _ <- esClient.existsIndex(elasticIndex).assert(false)
    } yield ()
  }

  private def start(view: ActiveViewDef) = {
    val spaces = spacesBuilder(view)
    for {
      compiled <- CompositeViewDef.compile(view, spaces, PipeChain.compile(_, registry), compositeStream, projections)
      _        <- spaces.init
      _        <- Projection(compiled, UIO.none, _ => UIO.unit, _ => UIO.unit)(batchConfig)
    } yield compiled
  }

  test("Indexing resources without rebuild") {
    val uuid   = UUID.randomUUID()
    val viewId = iri"https://bbp.epfl.ch/composite"
    val rev    = 1

    val view = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, noRebuild)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, rev, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, rev, prefix)

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
      _        <- mainCompleted.get.map(_.get(project1)).eventuallySome(1)
      _        <- mainCompleted.get.map(_.get(project2)).eventuallySome(1)
      _        <- mainCompleted.get.map(_.get(project3)).eventuallySome(1)
      _        <- rebuildCompleted.get.assert(Map.empty)
      _        <- projections.progress(view.ref, rev).eventually(expectedProgress)
      _        <- checkElasticSearchDocuments(
                    elasticIndex,
                    jsonContentOf("indexing/result_muse.json"),
                    jsonContentOf("indexing/result_red_hot.json")
                  ).eventually(())
      _        <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result.nt")).eventually(())
    } yield ()
  }

  test("Indexing resources including metadata and deprecated without rebuild") {
    val value = CompositeViewValue(
      NonEmptySet.of(
        projectSource.copy(includeDeprecated = true),
        crossProjectSource.copy(includeDeprecated = true),
        remoteProjectSource.copy(includeDeprecated = true)
      ),
      NonEmptySet.of(
        elasticSearchProjection.copy(includeMetadata = true, includeDeprecated = true),
        blazegraphProjection.copy(includeMetadata = true, includeDeprecated = true)
      ),
      None
    )

    val uuid   = UUID.randomUUID()
    val viewId = iri"https://bbp.epfl.ch/composite2"
    val rev    = 1

    val view = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, value)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, rev, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, rev, prefix)

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project2)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project3)).eventuallySome(1)
      _ <- rebuildCompleted.get.assert(Map.empty)
      _ <- checkElasticSearchDocuments(
             elasticIndex,
             jsonContentOf("indexing/result_muse_metadata.json"),
             jsonContentOf("indexing/result_red_hot_metadata.json")
           ).eventually(())
      _ <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result_metadata.nt")).eventually(())
    } yield ()
  }

  test("Indexing resources with interval restart") {
    val uuid = UUID.randomUUID()

    val rebuild = noRebuild.copy(rebuildStrategy = Some(CompositeView.Interval(2.seconds)))

    val viewId = iri"https://bbp.epfl.ch/composite3"
    val rev    = 1
    val view   = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, rebuild)

    val elasticIndex    = projectionIndex(elasticSearchProjection, uuid, rev, prefix)
    val sparqlNamespace = projectionNamespace(blazegraphProjection, uuid, rev, prefix)

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
        _ <- projections.progress(view.ref, rev).eventually(expectedProgress)
        _ <- checkElasticSearchDocuments(
               elasticIndex,
               jsonContentOf("indexing/result_muse.json"),
               jsonContentOf("indexing/result_red_hot.json")
             ).eventually(())
        _ <- checkBlazegraphTriples(sparqlNamespace, contentOf("indexing/result.nt")).eventually(())
      } yield ()

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project2)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project3)).eventuallySome(1)
      _ <- rebuildCompleted.get.map(_.get(project1)).eventuallySome(1)
      _ <- rebuildCompleted.get.map(_.get(project2)).eventuallySome(1)
      _ <- rebuildCompleted.get.map(_.get(project3)).eventuallySome(1)
      _ <- checkIndexingState
      _ <- projections.fullRestart(project1, viewId)(Anonymous)
      _ <- mainCompleted.get.map(_.get(project1)).eventuallySome(2)
      _ <- mainCompleted.get.map(_.get(project2)).eventuallySome(2)
      _ <- mainCompleted.get.map(_.get(project3)).eventuallySome(2)
      _ <- rebuildCompleted.get.map(_.get(project1)).eventuallySome(2)
      _ <- rebuildCompleted.get.map(_.get(project2)).eventuallySome(2)
      _ <- rebuildCompleted.get.map(_.get(project3)).eventuallySome(2)
      _ <- checkIndexingState
    } yield ()
  }

  test("Indexing resources with included JSON-LD context") {
    val value = CompositeViewValue(
      NonEmptySet.of(projectSource, crossProjectSource, remoteProjectSource),
      NonEmptySet.of(elasticSearchProjection.copy(includeContext = true)),
      None
    )

    val uuid = UUID.randomUUID()

    val viewId       = iri"https://bbp.epfl.ch/composite4"
    val rev          = 1
    val view         = ActiveViewDef(ViewRef(project1, viewId), uuid, rev, value)
    val elasticIndex = projectionIndex(elasticSearchProjection, uuid, rev, prefix)

    for {
      _ <- resetCompleted
      _ <- start(view)
      _ <- mainCompleted.get.map(_.get(project1)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project2)).eventuallySome(1)
      _ <- mainCompleted.get.map(_.get(project3)).eventuallySome(1)
      _ <- rebuildCompleted.get.assert(Map.empty)
      _ <- checkElasticSearchDocuments(
             elasticIndex,
             jsonContentOf("indexing/result_muse.json").deepMerge(contextJson.removeKeys(keywords.id)),
             jsonContentOf("indexing/result_red_hot.json").deepMerge(contextJson.removeKeys(keywords.id))
           ).eventually(())
    } yield ()
  }

  private def checkElasticSearchDocuments(index: IndexLabel, expected: Json*) = {
    val page = FromPagination(0, 5000)
    for {
      _       <- esClient.refresh(index)
      results <- esClient.search(
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

  private val checkQuery                                                  = SparqlConstructQuery.unsafe("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o} ORDER BY ?s")
  private def checkBlazegraphTriples(namespace: String, expected: String) =
    bgClient
      .query(Set(namespace), checkQuery, SparqlNTriples)
      .map(_.value.toString)
      .map(_.equalLinesUnordered(expected))
}

object CompositeIndexingSuite extends IOFixedClock {

  implicit private val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.never

  private val queryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Delay(10.millis))
  val batchConfig: BatchConfig         = BatchConfig(2, 50.millis)

  type Result = (ElasticSearchClient, BlazegraphClient, CompositeProjections, CompositeSpaces.Builder)

  private def resource()(implicit s: Scheduler, cl: ClassLoader): Resource[Task, Result] = {
    (Doobie.resource(), ElasticSearchClientSetup.resource(), BlazegraphClientSetup.resource()).parMapN {
      case (xas, esClient, bgClient) =>
        val compositeRestartStore = new CompositeRestartStore(xas)
        val projections           =
          CompositeProjections(compositeRestartStore, xas, queryConfig, batchConfig, 3.seconds)
        val spacesBuilder         = CompositeSpaces.Builder("delta", esClient, batchConfig, bgClient, batchConfig)(baseUri, rcr)
        (esClient, bgClient, projections, spacesBuilder)
    }
  }

  def suiteLocalFixture(name: String)(implicit s: Scheduler, cl: ClassLoader): TaskFixture[Result] =
    ResourceFixture.suiteLocal(name, resource())

  trait Fixture { self: BioSuite =>
    val compositeIndexing: ResourceFixture.TaskFixture[Result] = suiteLocalFixture("compositeIndexing")
  }

  private val ctxIri = ContextValue(iri"http://music.com/context")

  implicit val config: Configuration = Configuration.default.copy(
    transformMemberNames = {
      case "id"  => keywords.id
      case other => other
    }
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
  final case class Album(id: Iri, title: String, by: Iri)                      extends Music {
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
