package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.Sequence
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, ElasticSearchViewEvent, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{obj, predicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
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
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, Projection, SuccessMessage}
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
    contexts.elasticSearch         -> jsonContentOf("/contexts/elasticsearch.json"),
    contexts.elasticSearchIndexing -> jsonContentOf("/contexts/elasticsearch-indexing.json")
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

  val allowedPerms = Set(Permission.unsafe("views/query"))

  val perms        = PermissionsDummy(allowedPerms).accepted
  val org          = Label.unsafe("org")
  val base         = nxv.base
  val project1     = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.default)
  val project2     = ProjectGen.project("org", "proj2", base = base, mappings = ApiMappings.default)
  val projectRef   = project1.ref
  def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil
      )

  val config = ElasticSearchViewsConfig(
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    processor
  )

  implicit val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  val httpClient          = HttpClient()
  val esClient            = new ElasticSearchClient(httpClient, elasticsearchHost.endpoint)

  val views: ElasticSearchViews = (for {
    eventLog      <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (_, projects) <- projectSetup
    views         <- ElasticSearchViews(config, eventLog, projects, perms, esClient)
  } yield views).accepted

  val idPrefix = Iri.unsafe("https://example.com")

  val id1Proj1 = idPrefix / "id1Proj1"
  val id2Proj1 = idPrefix / "id2Proj1"
  val id3Proj1 = idPrefix / "id3Proj1"
  val id1Proj2 = idPrefix / "id1Proj2"
  val id2Proj2 = idPrefix / "id2Proj2"
  val id3Proj2 = idPrefix / "id3Proj2"

  val value1Proj1 = 1
  val value2Proj1 = 2
  val value3Proj1 = 3
  val value1Proj2 = 4
  val value2Proj2 = 5
  val value3Proj2 = 6

  val schema1 = idPrefix / "Schema1"
  val schema2 = idPrefix / "Schema2"

  val type1 = idPrefix / "Type1"
  val type2 = idPrefix / "Type2"

  val resource1Proj1 = resourceFor(id1Proj1, project1.ref, type1, false, schema1, value1Proj1)
  val resource2Proj1 = resourceFor(id2Proj1, project1.ref, type2, false, schema2, value2Proj1)
  val resource3Proj1 = resourceFor(id3Proj1, project1.ref, type1, true, schema1, value3Proj1)
  val resource1Proj2 = resourceFor(id1Proj2, project2.ref, type1, false, schema1, value1Proj2)
  val resource2Proj2 = resourceFor(id2Proj2, project2.ref, type2, false, schema2, value2Proj2)
  val resource3Proj2 = resourceFor(id3Proj2, project2.ref, type1, true, schema2, value3Proj2)

  val messages: List[Message[ResourceF[IndexingData]]] =
    List(resource1Proj1, resource2Proj1, resource3Proj1, resource1Proj2, resource2Proj2, resource3Proj2).zipWithIndex
      .map { case (res, i) =>
        SuccessMessage(Sequence(i.toLong), res.id.toString, i.toLong, res, Vector.empty)
      }
  val resourcesForProject                              = Map(
    project1.ref -> Set(resource1Proj1.id, resource2Proj1.id, resource3Proj1.id),
    project2.ref -> Set(resource1Proj2.id, resource2Proj2.id, resource3Proj2.id)
  )

  val eventLog = new GlobalMessageEventLogDummy[ResourceF[IndexingData]](
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

  implicit val patience: PatienceConfig =
    PatienceConfig(15.seconds, Span(1000, Millis))
//  implicit val bindingsOrdering: Ordering[Map[String, Binding]] =
//    Ordering.by(map => s"${map.keys.toSeq.sorted.mkString}${map.values.map(_.value).toSeq.sorted.mkString}")

  val page = FromPagination(0, 5000)
  "ElasticSearchIndexing" should {
    val _ = ElasticSearchIndexingCoordinator(views, eventLog, esClient, projection, config).accepted

    "index resources for project1" in {
      val project1View = views.create(viewId, project1.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(documentFor(resource1Proj1, value1Proj1), documentFor(resource2Proj1, value2Proj1))
      }

    }

    "index resources for project2" in {
      val project2View = views.create(viewId, project2.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project2View.value.uuid, project2View.rev)

      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(documentFor(resource1Proj2, value1Proj2), documentFor(resource2Proj2, value2Proj2))
      }
    }
    "index resources with metadata" in {
      val indexVal     = indexingValue.copy(includeMetadata = true)
      val project1View = views.update(viewId, project1.ref, 1L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(documentWithMetaFor(resource1Proj1, value1Proj1), documentWithMetaFor(resource2Proj1, value2Proj1))
      }
    }
    "index resources including deprecated" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true)
      val project1View = views.update(viewId, project1.ref, 2L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(
            documentFor(resource1Proj1, value1Proj1),
            documentFor(resource2Proj1, value2Proj1),
            documentFor(resource3Proj1, value3Proj1)
          )
      }
    }
    "index resources constrained by schema" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true, resourceSchemas = Set(schema1))
      val project1View = views.update(viewId, project1.ref, 3L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(documentFor(resource1Proj1, value1Proj1), documentFor(resource3Proj1, value3Proj1))

      }
    }
    "index resources with type" in {
      val indexVal     = indexingValue.copy(includeDeprecated = true, resourceTypes = Set(type2))
      val project1View = views.update(viewId, project1.ref, 4L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual List(documentFor(resource2Proj1, value2Proj1))
      }
    }
    "index resources with source" in {
      val indexVal     = indexingValue.copy(sourceAsText = false)
      val project1View = views.update(viewId, project1.ref, 5L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index        = IndexLabel.fromView(config.indexing.prefix, project1View.value.uuid, project1View.rev)
      eventually {
        val results = esClient.search(JsonObject.empty, Set(index.value))(page).accepted
        results.sources shouldEqual
          List(documentWithSourceFor(resource1Proj1, value1Proj1), documentWithSourceFor(resource2Proj1, value2Proj1))
      }
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
    val graph  = Graph.empty.copy(rootNode = id).add(predicate(skos.prefLabel), obj(s"name-$value"))
    ResourceF(
      id,
      ResourceUris.apply("resources", project, id)(ApiMappings.default, ProjectBase.unsafe(base)),
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
