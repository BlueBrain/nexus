package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.http.scaladsl.model.Uri.Query
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture.config
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingSpec.{Album, Band, Music}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingStream.{RemoteProjectsCounts, RestartProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.{CrossProjectSourceFields, ProjectSourceFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeView, CompositeViewFields, TemplateSparqlConstructQuery, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingSpec.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSourceDummy, IndexingStreamController}
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, ProjectsCounts, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit._
import com.whisk.docker.scalatest.DockerTestKit
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterEach, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.duration._

class CompositeIndexingSpec
    extends AbstractDBSpec
    with BlazegraphDocker
    with ElasticSearchDocker
    with DockerTestKit
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with TestMatchers
    with EitherValuable
    with Eventually
    with CirceLiteral
    with CirceEq
    with CompositeViewsSetup
    with ConfigFixtures
    with BeforeAndAfterEach {

  implicit private val patience: PatienceConfig =
    PatienceConfig(30.seconds, Span(1000, Millis))

  implicit private val uuidF: UUIDF     = UUIDF.random
  implicit private val sc: Scheduler    = Scheduler.global
  private val realm                     = Label.unsafe("myrealm")
  private val bob                       = User("Bob", realm)
  implicit private val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val allowedPerms = Set(permissions.query, events.read)

  private val (acls, perms) = AclSetup.initValuesWithPerms((bob, AclAddress.Root, allowedPerms)).accepted
  private val org           = Label.unsafe("org")
  private val base          = nxv.base
  private val project1      = ProjectGen.project("org", "proj", base = base)
  private val project2      = ProjectGen.project("org", "proj2", base = base)

  private def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil
      )

  implicit private val keyValueStoreConfig = keyValueStore

  implicit private val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  private val httpClient          = HttpClient()
  private val esClient            = new ElasticSearchClient(httpClient, elasticsearchHost.endpoint)
  private val blazeClient         = BlazegraphClient(httpClient, blazegraphHostConfig.endpoint, None)

  private val museId              = iri"http://music.com/muse"
  private val museUuid            = UUID.randomUUID()
  private val muse                = Band(museId, "Muse", 1994, Set("progressive rock", "alternative rock", "electronica"))
  private val absolutionId        = iri"http://music.com/absolution"
  private val absolutionUuid      = UUID.randomUUID()
  private val absolution          = Album(absolutionId, "Absolution", museId)
  private val blackHolesId        = iri"http://music.com/black_holes_and_revelations"
  private val blackHolesUuid      = UUID.randomUUID()
  private val blackHoles          = Album(blackHolesId, "Black Holes & Revelations", museId)
  private val redHotId            = iri"http://music.com/red_hot_chili_peppers"
  private val redHotUuid          = UUID.randomUUID()
  private val redHot              = Band(redHotId, "Red Hot Chilli Peppers", 1983, Set("funk rock", "alternative rock"))
  private val redHotUpdate        = Band(redHotId, "Red Hot Chilli Peppers", 1983, Set("funk rock", "rap rock"))
  private val bloodSugarId        = iri"http://music.com/blood_sugar_sex_magic"
  private val bloodSugarUuid      = UUID.randomUUID()
  private val bloodSugar          = Album(bloodSugarId, "Blood_Sugar_Sex_Magic", redHotId)
  private val californicationId   = iri"http://music.com/californication"
  private val californicationUuid = UUID.randomUUID()
  private val californication     = Album(californicationId, "Californication", redHotId)

  private val messages =
    List(
      exchangeValue(project1.ref, absolution, absolutionUuid)                    -> project1.ref,
      exchangeValue(project1.ref, blackHoles, blackHolesUuid, deprecated = true) -> project1.ref,
      exchangeValue(project1.ref, muse, museUuid)                                -> project1.ref,
      exchangeValue(project2.ref, californication, californicationUuid)          -> project2.ref,
      exchangeValue(project2.ref, bloodSugar, bloodSugarUuid)                    -> project2.ref,
      exchangeValue(project2.ref, redHot, redHotUuid)                            -> project2.ref,
      exchangeValue(project2.ref, redHotUpdate, redHotUuid, rev = 2)             -> project2.ref
    ).zipWithIndex.foldLeft(Map.empty[ProjectRef, Seq[Message[EventExchangeValue[_, _]]]]) {
      case (acc, ((res, project), i)) =>
        val entry = SuccessMessage(
          Sequence(i.toLong),
          Instant.EPOCH,
          res.value.toResource.id.toString,
          i.toLong,
          res,
          Vector.empty
        )
        acc.updatedWith(project)(seqOpt => Some(seqOpt.getOrElse(Seq.empty) :+ entry))
    }

  private val indexingSource = new IndexingSourceDummy(messages.map { case (k, v) => (k, None) -> v })

  private val projection = Projection.inMemory(()).accepted

  private val cache: KeyValueStore[ProjectionId, ProjectionProgress[Unit]] =
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "CompositeViewsProgress",
      (_, progress) =>
        progress.offset match {
          case Sequence(v) => v
          case _           => 0L
        }
    )

  private val viewId             = iri"https://example.com"
  private val context            = jsonContentOf("indexing/music-context.json").topContextValueOrEmpty.asInstanceOf[ContextObject]
  private val source1Id          = iri"https://example.com/source1"
  private val source2Id          = iri"https://example.com/source2"
  private val projection1Id      = iri"https://example.com/projection1"
  private val projection2Id      = iri"https://example.com/projection2"
  private val projectSource      = ProjectSourceFields(Some(source1Id))
  private val crossProjectSource = CrossProjectSourceFields(Some(source2Id), project2.ref, Set(bob))
  private val query              = TemplateSparqlConstructQuery(contentOf("indexing/query.txt")).toOption.value

  private val elasticSearchProjection                                   = ElasticSearchProjectionFields(
    Some(projection1Id),
    query,
    jsonObjectContentOf("indexing/mapping.json"),
    context,
    resourceTypes = Set(iri"http://music.com/Band")
  )
  private val blazegraphProjection                                      = SparqlProjectionFields(
    Some(projection2Id),
    query,
    resourceTypes = Set(iri"http://music.com/Band")
  )
  private val projectsCountsCache: MutableMap[ProjectRef, ProjectCount] = MutableMap.empty
  private val restartProjectionsCache                                   = MutableMap.empty[(Iri, ProjectRef, Set[CompositeViewProjectionId]), Int]

  private val initCount = ProjectCount(1, Instant.EPOCH)

  private val projectsCounts = new ProjectsCounts {
    override def get(): UIO[ProjectCountsCollection] =
      UIO.delay(ProjectCountsCollection(projectsCountsCache.toMap))

    override def get(project: ProjectRef): UIO[Option[ProjectCount]] =
      UIO.delay(projectsCountsCache.get(project))
  }

  private val remoteProjectsCounts: RemoteProjectsCounts = _ => UIO.delay(None)

  private val restartProjections: RestartProjections =
    (iri, projectRef, projectionIds) =>
      UIO
        .delay(
          restartProjectionsCache.updateWith((iri, projectRef, projectionIds))(count => Some(count.fold(1)(_ + 1)))
        )
        .void

  private val indexingStream = new CompositeIndexingStream(
    config.elasticSearchIndexing,
    esClient,
    config.blazegraphIndexing,
    blazeClient,
    cache,
    projectsCounts,
    remoteProjectsCounts,
    restartProjections,
    projection,
    indexingSource
  )

  private val (orgs, projects)      = projectSetup.accepted
  private val indexingController    = new IndexingStreamController[CompositeView](CompositeViews.moduleType)
  private val views: CompositeViews =
    initViews(
      orgs,
      projects,
      perms,
      acls,
      esClient,
      Crypto("password", "salt"),
      config.copy(minIntervalRebuild = 900.millis)
    ).accepted
  CompositeIndexingCoordinator(views, indexingController, indexingStream, config).runAsyncAndForget

  private def exchangeValue[A <: Music: Encoder](
      project: ProjectRef,
      value: A,
      uuid: UUID,
      deprecated: Boolean = false,
      rev: Long = 1L
  )(implicit
      caller: Caller,
      jsonldEncoder: JsonLdEncoder[A]
  ): EventExchangeValue[A, Metadata] = {
    val resource = ResourceF(
      value.id,
      ResourceUris.apply("resources", project, value.id)(Resources.mappings, ProjectBase.unsafe(base)),
      rev,
      Set(value.tpe),
      deprecated,
      Instant.EPOCH,
      caller.subject,
      Instant.EPOCH,
      caller.subject,
      Latest(schemas.resources),
      value
    )
    val metadata = JsonLdValue(Metadata(uuid))
    EventExchangeValue(ReferenceExchangeValue(resource, resource.value.asJson, jsonldEncoder), metadata)
  }

  private val page = FromPagination(0, 5000)

  private def ntriplesFrom(index: String): NTriples =
    blazeClient
      .queryNTriples(
        Set(index),
        SparqlConstructQuery("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o} ORDER BY ?s").toOption.value
      )
      .accepted

  override protected def beforeEach(): Unit = {
    projectsCountsCache.clear()
    projectsCountsCache.addOne(project1.ref -> initCount).addOne(project2.ref -> initCount)
    restartProjectionsCache.clear()
    super.beforeEach()
  }

  private def restartedProjections(view: ViewResource, sourceIds: Set[Iri], times: Int) =
    MutableMap(
      (
        viewId,
        project1.ref,
        CompositeViews.projectionIds(view.value, view.rev).collect {
          case (sId, _, projection) if sourceIds.contains(sId) => projection
        }
      ) -> times
    )

  "CompositeIndexing" should {

    "index resources" in {
      val view   = CompositeViewFields(
        NonEmptySet.of(projectSource, crossProjectSource),
        NonEmptySet.of(elasticSearchProjection, blazegraphProjection),
        None
      )
      val result = views.create(viewId, project1.ref, view).accepted
      checkElasticSearchDocuments(
        result,
        jsonContentOf("indexing/result_muse.json"),
        jsonContentOf("indexing/result_red_hot.json")
      )
      checkBlazegraphTriples(result, contentOf("indexing/result.nt"))
    }

    "index resources with metadata and deprecated" in {
      val view   = CompositeViewFields(
        NonEmptySet.of(projectSource.copy(includeDeprecated = true), crossProjectSource.copy(includeDeprecated = true)),
        NonEmptySet
          .of(
            elasticSearchProjection.copy(includeMetadata = true, includeDeprecated = true),
            blazegraphProjection.copy(includeMetadata = true, includeDeprecated = true)
          ),
        None
      )
      val result = views.update(viewId, project1.ref, 1, view).accepted
      checkElasticSearchDocuments(
        result,
        jsonContentOf("indexing/result_muse_metadata.json", "uuid"    -> museUuid),
        jsonContentOf("indexing/result_red_hot_metadata.json", "uuid" -> redHotUuid)
      )
      checkBlazegraphTriples(
        result,
        contentOf("indexing/result_metadata.nt", "muse_uuid" -> museUuid, "red_hot_uuid" -> redHotUuid)
      )

    }

    "index resources with interval restart" in {
      val view          = CompositeViewFields(
        NonEmptySet.of(projectSource, crossProjectSource),
        NonEmptySet.of(elasticSearchProjection, blazegraphProjection),
        Some(CompositeView.Interval(2500.millis))
      )
      val modifiedCount = initCount.copy(value = initCount.value + 1)
      val result        = views.update(viewId, project1.ref, 2, view).accepted

      Thread.sleep(1 * 3000)
      restartProjectionsCache shouldBe empty

      projectsCountsCache.addOne(project1.ref -> modifiedCount)
      Thread.sleep(1 * 3000)
      val expected1 = restartedProjections(result, Set(source1Id), times = 1)
      restartProjectionsCache shouldEqual expected1

      projectsCountsCache.addOne(project2.ref -> modifiedCount)
      Thread.sleep(1 * 3000)
      val expected2 = expected1 ++ restartedProjections(result, Set(source2Id), times = 1)
      restartProjectionsCache shouldEqual expected2

      checkElasticSearchDocuments(
        result,
        jsonContentOf("indexing/result_muse.json"),
        jsonContentOf("indexing/result_red_hot.json")
      )
      checkBlazegraphTriples(result, contentOf("indexing/result.nt"))
    }

  }

  private def checkElasticSearchDocuments(view: ViewResource, expected: Json*) = {
    def idx(view: ViewResource) = view.value.projections.value.collectFirst {
      case es: ElasticSearchProjection if es.id == projection1Id =>
        IndexLabel.unsafe(s"${config.elasticSearchIndexing.prefix}_${view.value.uuid}_${es.uuid}_${view.rev}")
    }.value

    eventually {
      val results = esClient
        .search(
          QueryBuilder.empty.withSort(SortList(List(Sort("@id")))).withPage(page),
          Set(idx(view).value),
          Query.Empty
        )
        .accepted
      results.sources.size shouldEqual expected.size
      forAll(results.sources.zip(expected)) { case (source, expected) =>
        source.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    if (view.rev > 1L) {
      val previous = views.fetchAt(view.id, view.value.project, view.rev - 1L).accepted
      eventually {
        esClient.existsIndex(idx(previous)).accepted shouldEqual false
      }
    }
  }

  private def checkBlazegraphTriples(view: ViewResource, expected: String) = {
    def ns(view: ViewResource) = view.value.projections.value.collectFirst {
      case sparql: SparqlProjection if sparql.id == projection2Id =>
        s"${config.blazegraphIndexing.prefix}_${view.value.uuid}_${sparql.uuid}_${view.rev}"
    }.value

    eventually {
      ntriplesFrom(ns(view)).toString should equalLinesUnordered(expected)
    }

    if (view.rev > 1L) {
      val previous = views.fetchAt(view.id, view.value.project, view.rev - 1L).accepted
      eventually {
        blazeClient.existsNamespace(ns(previous)).accepted shouldEqual false
      }
    }
  }
}

object CompositeIndexingSpec {
  val ctxIri = ContextValue(iri"http://music.com/context")

  implicit val config: Configuration = Configuration.default.copy(
    transformMemberNames = {
      case "id"  => keywords.id
      case other => other
    }
  )

  sealed trait Music extends Product with Serializable {
    def id: Iri
    def tpe: Iri
  }

  final case class Band(id: Iri, name: String, start: Int, genre: Set[String]) extends Music {
    override val tpe: Iri = iri"http://music.com/Band"
  }
  object Band {
    implicit val bandEncoder: Encoder.AsObject[Band]    =
      deriveConfiguredEncoder[Band].mapJsonObject(_.add("@type", "Band".asJson))
    implicit val bandJsonLdEncoder: JsonLdEncoder[Band] =
      JsonLdEncoder.computeFromCirce((b: Band) => b.id, ctxIri)
  }
  final case class Album(id: Iri, title: String, by: Iri)                      extends Music {
    override val tpe: Iri = iri"http://music.com/Album"
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
