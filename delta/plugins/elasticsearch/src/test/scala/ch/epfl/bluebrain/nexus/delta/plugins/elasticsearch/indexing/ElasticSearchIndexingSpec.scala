package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.Uri.Query
import akka.persistence.query.Sequence
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, ElasticSearchViewEvent, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{obj, predicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.JsonObject
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{CancelAfterFailure, DoNotDiscover, EitherValues, Inspectors}

import java.time.Instant
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
    with Eventually {

  implicit val uuidF: UUIDF                 = UUIDF.random
  implicit val sc: Scheduler                = Scheduler.global
  val realm                                 = Label.unsafe("myrealm")
  val bob                                   = User("Bob", realm)
  implicit val caller: Caller               = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata   -> jsonContentOf("/contexts/metadata.json"),
    contexts.elasticsearch         -> jsonContentOf("/contexts/elasticsearch.json"),
    contexts.elasticsearchIndexing -> jsonContentOf("/contexts/elasticsearch-indexing.json")
  )

  val viewId = IriSegment(Iri.unsafe("https://example.com"))

  val indexingValue = IndexingElasticSearchViewValue(
    Set.empty,
    Set.empty,
    None,
    includeMetadata = false,
    includeDeprecated = false,
    sourceAsText = true,
    mapping = jsonContentOf("/mapping.json"),
    permission = Permission.unsafe("views/query")
  )

  val defaultMappings = ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources)

  val allowedPerms = Set(Permission.unsafe("views/query"))

  val perms        = PermissionsDummy(allowedPerms).accepted
  val org          = Label.unsafe("org")
  val base         = nxv.base
  val project1     = ProjectGen.project("org", "proj", base = base)
  val project2     = ProjectGen.project("org", "proj2", base = base)
  val projectRef   = project1.ref
  def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil,
        defaultMappings
      )

  val config = ElasticSearchViewsConfig(
    "http://localhost",
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    externalIndexing
  )
  val acls   = AclSetup.init().accepted

  implicit val kvCfg: KeyValueStoreConfig          = config.keyValueStore
  implicit val externalCfg: ExternalIndexingConfig = config.indexing

  implicit val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  val httpClient          = HttpClient()
  val esClient            = new ElasticSearchClient(httpClient, elasticsearchHost.endpoint)

  val idPrefix = Iri.unsafe("https://example.com")

  val id1Proj1 = idPrefix / "id1Proj1"
  val id2Proj1 = idPrefix / "id2Proj1"
  val id3Proj1 = idPrefix / "id3Proj1"
  val id1Proj2 = idPrefix / "id1Proj2"
  val id2Proj2 = idPrefix / "id2Proj2"
  val id3Proj2 = idPrefix / "id3Proj2"

  val value1Proj1     = 1
  val value2Proj1     = 2
  val value3Proj1     = 3
  val value1Proj2     = 4
  val value2Proj2     = 5
  val value3Proj2     = 6
  val value1rev2Proj1 = 7

  val schema1 = idPrefix / "Schema1"
  val schema2 = idPrefix / "Schema2"

  val type1 = idPrefix / "Type1"
  val type2 = idPrefix / "Type2"
  val type3 = idPrefix / "Type3"

  val res1Proj1     = resourceFor(id1Proj1, project1.ref, type1, false, schema1, value1Proj1)
  val res2Proj1     = resourceFor(id2Proj1, project1.ref, type2, false, schema2, value2Proj1)
  val res3Proj1     = resourceFor(id3Proj1, project1.ref, type1, true, schema1, value3Proj1)
  val res1Proj2     = resourceFor(id1Proj2, project2.ref, type1, false, schema1, value1Proj2)
  val res2Proj2     = resourceFor(id2Proj2, project2.ref, type2, false, schema2, value2Proj2)
  val res3Proj2     = resourceFor(id3Proj2, project2.ref, type1, true, schema2, value3Proj2)
  val res1rev2Proj1 = resourceFor(id1Proj1, project1.ref, type3, false, schema1, value1rev2Proj1)

  val messages: List[Message[ResourceF[IndexingData]]] =
    List(res1Proj1, res2Proj1, res3Proj1, res1Proj2, res2Proj2, res3Proj2, res1rev2Proj1).zipWithIndex
      .map { case (res, i) =>
        SuccessMessage(Sequence(i.toLong), res.updatedAt, res.id.toString, i.toLong, res, Vector.empty)
      }
  val resourcesForProject                              = Map(
    project1.ref -> Set(res1Proj1.id, res2Proj1.id, res3Proj1.id, res1rev2Proj1.id),
    project2.ref -> Set(res1Proj2.id, res2Proj2.id, res3Proj2.id)
  )

  val globalEventLog = new GlobalMessageEventLogDummy[ResourceF[IndexingData]](
    messages,
    (projectRef, msg) => {
      msg match {
        case success: SuccessMessage[ResourceF[IndexingData]] =>
          resourcesForProject.getOrElse(projectRef, Set.empty).contains(success.value.id)
        case _                                                => false
      }
    },
    (_, _) => true,
    (_, _) => true
  )

  val projection = Projection.inMemory(()).accepted

  val cache: KeyValueStore[ProjectionId, ProjectionProgress[Unit]] =
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "ElasticSearchIndexingViewsProgress",
      (_, progress) =>
        progress.offset match {
          case Sequence(v) => v
          case _           => 0L
        }
    )

  implicit val patience: PatienceConfig =
    PatienceConfig(15.seconds, Span(1000, Millis))

  val views: ElasticSearchViews = (for {
    eventLog      <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (_, projects) <- projectSetup
    coordinator   <- ElasticSearchIndexingCoordinator(globalEventLog, esClient, projection, cache, config)
    views         <- ElasticSearchViews(config, eventLog, projects, perms, esClient, coordinator)
  } yield views).accepted

  private def listAll(index: IndexLabel) =
    esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted

  val page = FromPagination(0, 5000)
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
          List(documentWithMetaFor(res2Proj1, value2Proj1), documentWithMetaFor(res1rev2Proj1, value1rev2Proj1))
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
    "index resources with source" in {
      val indexVal     = indexingValue.copy(sourceAsText = false)
      val project1View = views.update(viewId, project1.ref, 5L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.unsafe(project1View.index)
      eventually {
        listAll(index).sources shouldEqual
          List(documentWithSourceFor(res2Proj1, value2Proj1), documentWithSourceFor(res1rev2Proj1, value1rev2Proj1))
      }
      val previous     = views.fetchAt(viewId, project1.ref, 5L).accepted.asInstanceOf[IndexingViewResource]
      esClient.existsIndex(IndexLabel.unsafe(previous.index)).accepted shouldEqual false
    }
  }

  def documentWithMetaFor(resource: ResourceF[IndexingData], intValue: Int) =
    documentFor(resource, intValue) deepMerge
      resource.void.toCompactedJsonLd.accepted.json.asObject.value.remove(keywords.context)

  def documentWithSourceFor(resource: ResourceF[IndexingData], intValue: Int) =
    resource.value.source.asObject.value deepMerge
      JsonObject(keywords.id -> resource.id.asJson, "prefLabel" -> s"name-$intValue".asJson)

  def documentFor(resource: ResourceF[IndexingData], intValue: Int)           =
    JsonObject(
      keywords.id        -> resource.id.asJson,
      "_original_source" -> resource.value.source.noSpaces.asJson,
      "prefLabel"        -> s"name-$intValue".asJson
    )

  def resourceFor(id: Iri, project: ProjectRef, tpe: Iri, deprecated: Boolean, schema: Iri, value: Int)(implicit
      caller: Caller
  ): ResourceF[IndexingData] = {
    val source = jsonContentOf(
      "/indexing/resource.json",
      "id"     -> id,
      "type"   -> tpe,
      "number" -> value.toString,
      "name"   -> s"name-$value"
    )
    val graph  = Graph.empty(id).add(predicate(skos.prefLabel), obj(s"name-$value"))
    ResourceF(
      id,
      ResourceUris.apply("resources", project, id)(defaultMappings, ProjectBase.unsafe(base)),
      1L,
      Set(tpe),
      deprecated,
      Instant.EPOCH,
      caller.subject,
      Instant.EPOCH,
      caller.subject,
      Latest(schema),
      IndexingData(graph, source)
    )
  }

}
