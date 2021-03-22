package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.Uri.Query
import akka.persistence.query.Sequence
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{obj, predicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{CancelAfterFailure, DoNotDiscover, EitherValues, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchIndexingSpec
    extends AbstractDBSpec
    with EitherValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CancelAfterFailure
    with ConfigFixtures
    with Eventually
    with RemoteContextResolutionFixture {

  implicit private val uuidF: UUIDF     = UUIDF.random
  implicit private val sc: Scheduler    = Scheduler.global
  private val realm                     = Label.unsafe("myrealm")
  private val bob                       = User("Bob", realm)
  implicit private val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val viewId = IriSegment(Iri.unsafe("https://example.com"))

  private val indexingValue = IndexingElasticSearchViewValue(
    Set.empty,
    Set.empty,
    None,
    includeMetadata = false,
    includeDeprecated = false,
    sourceAsText = true,
    mapping = jsonContentOf("/mapping.json"),
    permission = Permission.unsafe("views/query")
  )

  private val allowedPerms = Set(Permission.unsafe("views/query"))

  private val perms        = PermissionsDummy(allowedPerms).accepted
  private val org          = Label.unsafe("org")
  private val base         = nxv.base
  private val project1     = ProjectGen.project("org", "proj", base = base)
  private val project2     = ProjectGen.project("org", "proj2", base = base)
  private def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil
      )

  private val config = ElasticSearchViewsConfig(
    "http://localhost",
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    externalIndexing
  )

  implicit private val kvCfg: KeyValueStoreConfig          = config.keyValueStore
  implicit private val externalCfg: ExternalIndexingConfig = config.indexing

  implicit private val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  private val httpClient          = HttpClient()
  private val esClient            = new ElasticSearchClient(httpClient, elasticsearchHost.endpoint)

  private val idPrefix = Iri.unsafe("https://example.com")

  private val id1Proj1 = idPrefix / "id1Proj1"
  private val id2Proj1 = idPrefix / "id2Proj1"
  private val id3Proj1 = idPrefix / "id3Proj1"
  private val id1Proj2 = idPrefix / "id1Proj2"
  private val id2Proj2 = idPrefix / "id2Proj2"
  private val id3Proj2 = idPrefix / "id3Proj2"

  private val value1Proj1     = 1
  private val value2Proj1     = 2
  private val value3Proj1     = 3
  private val value1Proj2     = 4
  private val value2Proj2     = 5
  private val value3Proj2     = 6
  private val value1rev2Proj1 = 7

  private val uuid1Proj1     = UUID.randomUUID()
  private val uuid2Proj1     = UUID.randomUUID()
  private val uuid3Proj1     = UUID.randomUUID()
  private val uuid1Proj2     = UUID.randomUUID()
  private val uuid2Proj2     = UUID.randomUUID()
  private val uuid3Proj2     = UUID.randomUUID()
  private val uuid1rev2Proj1 = UUID.randomUUID()

  private val schema1 = idPrefix / "Schema1"
  private val schema2 = idPrefix / "Schema2"

  private val type1 = idPrefix / "Type1"
  private val type2 = idPrefix / "Type2"
  private val type3 = idPrefix / "Type3"

  private val res1Proj1     = resourceFor(id1Proj1, project1.ref, type1, false, schema1, value1Proj1, uuid1Proj1)
  private val res2Proj1     = resourceFor(id2Proj1, project1.ref, type2, false, schema2, value2Proj1, uuid2Proj1)
  private val res3Proj1     = resourceFor(id3Proj1, project1.ref, type1, true, schema1, value3Proj1, uuid3Proj1)
  private val res1Proj2     = resourceFor(id1Proj2, project2.ref, type1, false, schema1, value1Proj2, uuid1Proj2)
  private val res2Proj2     = resourceFor(id2Proj2, project2.ref, type2, false, schema2, value2Proj2, uuid2Proj2)
  private val res3Proj2     = resourceFor(id3Proj2, project2.ref, type1, true, schema2, value3Proj2, uuid3Proj2)
  private val res1rev2Proj1 =
    resourceFor(id1Proj1, project1.ref, type3, false, schema1, value1rev2Proj1, uuid1rev2Proj1)

  private val messages: Map[ProjectRef, Seq[Message[ResourceF[IndexingData]]]] =
    List(
      res1Proj1     -> project1.ref,
      res2Proj1     -> project1.ref,
      res3Proj1     -> project1.ref,
      res1Proj2     -> project2.ref,
      res2Proj2     -> project2.ref,
      res3Proj2     -> project2.ref,
      res1rev2Proj1 -> project1.ref
    ).zipWithIndex.foldLeft(Map.empty[ProjectRef, Seq[Message[ResourceF[IndexingData]]]]) {
      case (acc, ((res, project), i)) =>
        val entry = SuccessMessage(Sequence(i.toLong), res.updatedAt, res.id.toString, i.toLong, res, Vector.empty)
        acc.updatedWith(project)(seqOpt => Some(seqOpt.getOrElse(Seq.empty) :+ entry))
    }

  private val indexingEventLog = new ElasticSearchIndexingEventLogDummy(messages.map { case (k, v) => (k, None) -> v })

  private val projection = Projection.inMemory(()).accepted

  private val cache: KeyValueStore[ProjectionId, ProjectionProgress[Unit]] =
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "ElasticSearchIndexingViewsProgress",
      (_, progress) =>
        progress.offset match {
          case Sequence(v) => v
          case _           => 0L
        }
    )

  implicit private val patience: PatienceConfig =
    PatienceConfig(15.seconds, Span(1000, Millis))

  val views: ElasticSearchViews = (for {
    eventLog       <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projs)  <- projectSetup
    resolverContext = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    coordinator    <- ElasticSearchIndexingCoordinator(indexingEventLog, esClient, projection, cache, config)
    views          <- ElasticSearchViews(config, eventLog, resolverContext, orgs, projs, perms, esClient, coordinator)
  } yield views).accepted

  private def listAll(index: IndexLabel) =
    esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted

  private val page = FromPagination(0, 5000)

  "ElasticSearchIndexing" should {

    "index resources for project1" in {
      val project1View = views.create(viewId, project1.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        val results = esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted
        results.sources shouldEqual
          List(documentFor(res2Proj1, value2Proj1), documentFor(res1rev2Proj1, value1rev2Proj1))
      }

    }

    "index resources for project2" in {
      val project2View = views.create(viewId, project2.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project2View.index)

      eventually {
        listAll(index).sources shouldEqual
          List(documentFor(res1Proj2, value1Proj2), documentFor(res2Proj2, value2Proj2))
      }
    }
    "index resources with metadata" in {
      val indexVal     = indexingValue.copy(includeMetadata = true)
      val project1View = views.update(viewId, project1.ref, 1L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual
          List(
            documentWithMetaFor(res2Proj1, value2Proj1, uuid2Proj1),
            documentWithMetaFor(res1rev2Proj1, value1rev2Proj1, uuid1rev2Proj1)
          )
      }
    }
    "index resources including deprecated" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true)
      val project1View = views.update(viewId, project1.ref, 2L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual
          List(
            documentFor(res2Proj1, value2Proj1),
            documentFor(res3Proj1, value3Proj1),
            documentFor(res1rev2Proj1, value1rev2Proj1)
          )
      }
    }
    "index resources constrained by schema" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true, resourceSchemas = Set(schema1))
      val project1View = views.update(viewId, project1.ref, 3L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual
          List(documentFor(res3Proj1, value3Proj1), documentFor(res1rev2Proj1, value1rev2Proj1))
      }
    }
    "cache projection for view" in {
      val projectionId = views.fetch(viewId, project1.ref).accepted.asInstanceOf[IndexingViewResource].projectionId
      cache.get(projectionId).accepted.value shouldEqual ProjectionProgress(Sequence(6), Instant.EPOCH, 4, 1, 0, 0)
    }
    "index resources with type" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true, resourceTypes = Set(type1))
      val project1View = views.update(viewId, project1.ref, 4L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual List(documentFor(res3Proj1, value3Proj1))
      }
    }
    "index resources without source" in {
      val indexVal     = indexingValue.copy(sourceAsText = false)
      val project1View = views.update(viewId, project1.ref, 5L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual
          List(
            documentWithoutSourceFor(res2Proj1, value2Proj1),
            documentWithoutSourceFor(res1rev2Proj1, value1rev2Proj1)
          )
      }
      val previous     = views.fetchAt(viewId, project1.ref, 5L).accepted.asInstanceOf[IndexingViewResource]
      esClient.existsIndex(IndexLabel.unsafe(previous.index)).accepted shouldEqual false
    }
  }

  def documentWithMetaFor(resource: ResourceF[IndexingData], intValue: Int, uuid: UUID) =
    documentFor(resource, intValue) deepMerge
      resource.void.toCompactedJsonLd.accepted.json.asObject.value.remove(keywords.context) deepMerge
      JsonObject("_uuid" -> uuid.asJson)

  def documentWithoutSourceFor(resource: ResourceF[IndexingData], intValue: Int)        =
    resource.value.source.asObject.value.removeAllKeys(keywords.context) deepMerge
      JsonObject(keywords.id -> resource.id.asJson, "prefLabel" -> s"name-$intValue".asJson)

  def documentFor(resource: ResourceF[IndexingData], intValue: Int)                     =
    JsonObject(
      keywords.id        -> resource.id.asJson,
      "_original_source" -> resource.value.source.noSpaces.asJson,
      "prefLabel"        -> s"name-$intValue".asJson
    )

  def resourceFor(id: Iri, project: ProjectRef, tpe: Iri, deprecated: Boolean, schema: Iri, value: Int, uuid: UUID)(
      implicit caller: Caller
  ): ResourceF[IndexingData] = {
    val source    = jsonContentOf(
      "/indexing/resource.json",
      "id"     -> id,
      "type"   -> tpe,
      "number" -> value.toString,
      "name"   -> s"name-$value"
    )
    val graph     = Graph.empty(id).add(predicate(skos.prefLabel), obj(s"name-$value"))
    val metaGraph = Graph.empty(id).add(predicate(nxv + "uuid"), obj(uuid.toString))
    ResourceF(
      id,
      ResourceUris.apply("resources", project, id)(Resources.mappings, ProjectBase.unsafe(base)),
      1L,
      Set(tpe),
      deprecated,
      Instant.EPOCH,
      caller.subject,
      Instant.EPOCH,
      caller.subject,
      Latest(schema),
      IndexingData(graph, metaGraph, source)
    )
  }

}
