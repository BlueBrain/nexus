package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.Uri.Query
import akka.persistence.query.Sequence
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingSpec.{Metadata, Value}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, ElasticSearchViews, ElasticSearchViewsSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSourceDummy, IndexingStreamController}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchIndexingSpec
    extends AbstractDBSpec
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CancelAfterFailure
    with ElasticSearchClientSetup
    with ConfigFixtures
    with EitherValuable
    with Eventually
    with Fixtures {

  implicit private val uuidF: UUIDF     = UUIDF.random
  private val realm                     = Label.unsafe("myrealm")
  private val bob                       = User("Bob", realm)
  implicit private val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val viewId = IriSegment(Iri.unsafe("https://example.com"))

  private val indexingValue = IndexingElasticSearchViewValue(
    None,
    IndexingElasticSearchViewValue.defaultPipeline :+ SourceAsText(),
    mapping = Some(jsonObjectContentOf("/mapping.json")),
    settings = None,
    context = None,
    permission = permissions.query
  )

  private val org      = Label.unsafe("org")
  private val base     = nxv.base
  private val project1 = ProjectGen.project("org", "proj", base = base)
  private val project2 = ProjectGen.project("org", "proj2", base = base)

  private val config = ElasticSearchViewsConfig(
    "http://localhost",
    None,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    10,
    1.minute,
    Refresh.False,
    2000
  )

  implicit private val kvCfg: KeyValueStoreConfig          = config.keyValueStore
  implicit private val externalCfg: ExternalIndexingConfig = config.indexing

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

  private val res1Proj1     = exchangeValue(id1Proj1, project1.ref, type1, false, schema1, value1Proj1, uuid1Proj1)
  private val res2Proj1     = exchangeValue(id2Proj1, project1.ref, type2, false, schema2, value2Proj1, uuid2Proj1)
  private val res3Proj1     = exchangeValue(id3Proj1, project1.ref, type1, true, schema1, value3Proj1, uuid3Proj1)
  private val res1Proj2     = exchangeValue(id1Proj2, project2.ref, type1, false, schema1, value1Proj2, uuid1Proj2)
  private val res2Proj2     = exchangeValue(id2Proj2, project2.ref, type2, false, schema2, value2Proj2, uuid2Proj2)
  private val res3Proj2     = exchangeValue(id3Proj2, project2.ref, type1, true, schema2, value3Proj2, uuid3Proj2)
  private val res1rev2Proj1 =
    exchangeValue(id1Proj1, project1.ref, type3, false, schema1, value1rev2Proj1, uuid1rev2Proj1)

  private val messages =
    List(
      res1Proj1     -> project1.ref,
      res2Proj1     -> project1.ref,
      res3Proj1     -> project1.ref,
      res1Proj2     -> project2.ref,
      res2Proj2     -> project2.ref,
      res3Proj2     -> project2.ref,
      res1rev2Proj1 -> project1.ref
    ).zipWithIndex.foldLeft(Map.empty[ProjectRef, Seq[Message[EventExchangeValue[_, _]]]]) {
      case (acc, ((res, project), i)) =>
        val entry = SuccessMessage(
          Sequence(i.toLong),
          Instant.EPOCH,
          res.value.resource.id.toString,
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
      "ElasticSearchViewsProgress",
      (_, progress) =>
        progress.offset match {
          case Sequence(v) => v
          case _           => 0L
        }
    )

  implicit private val patience: PatienceConfig =
    PatienceConfig(15.seconds, Span(1000, Millis))

  private val indexingStream = new ElasticSearchIndexingStream(
    esClient,
    indexingSource,
    cache,
    PipeConfig.coreConfig.rightValue,
    config,
    projection
  )

  private val (orgs, projs)             = ProjectSetup.init(org :: Nil, project1 :: project2 :: Nil).accepted
  private val indexingCleanup           = new ElasticSearchIndexingCleanup(esClient, cache, projection)
  private val views: ElasticSearchViews = ElasticSearchViewsSetup.init(orgs, projs, permissions.query)
  private val controller                = new IndexingStreamController[IndexingElasticSearchView](ElasticSearchViews.moduleType)
  ElasticSearchIndexingCoordinator(views, controller, indexingStream, indexingCleanup, config).accepted

  private def listAll(index: IndexLabel) =
    esClient.search(QueryBuilder.empty.withPage(page), Set(index.value), Query.Empty).accepted

  private val page = FromPagination(0, 5000)

  "ElasticSearchIndexing" should {

    "index resources for project1" in {
      val view = views.create(viewId, project1.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(
        view,
        documentFor(res2Proj1, value2Proj1),
        documentFor(res1rev2Proj1, value1rev2Proj1)
      )
    }
    "index resources for project2" in {
      val view = views.create(viewId, project2.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(view, documentFor(res1Proj2, value1Proj2), documentFor(res2Proj2, value2Proj2))
    }
    "index resources with metadata" in {
      val indexVal = indexingValue.copy(pipeline = indexingValue.pipeline.filterNot(_ == DiscardMetadata()))
      val view     = views.update(viewId, project1.ref, 1L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(
        view,
        documentWithMetaFor(res2Proj1, value2Proj1, uuid2Proj1),
        documentWithMetaFor(res1rev2Proj1, value1rev2Proj1, uuid1rev2Proj1)
      )
    }
    "index resources including deprecated" in {
      val indexVal = indexingValue.copy(pipeline = indexingValue.pipeline.filterNot(_ == FilterDeprecated()))
      val view     = views.update(viewId, project1.ref, 2L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(
        view,
        documentFor(res2Proj1, value2Proj1),
        documentFor(res3Proj1, value3Proj1),
        documentFor(res1rev2Proj1, value1rev2Proj1)
      )
    }

    "index resources constrained by schema" in {
      val indexVal =
        indexingValue.copy(pipeline =
          List(FilterBySchema(Set(schema1))) ++ indexingValue.pipeline.filterNot(
            _ == FilterDeprecated()
          )
        )
      val view     = views.update(viewId, project1.ref, 3L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(
        view,
        documentFor(res3Proj1, value3Proj1),
        documentFor(res1rev2Proj1, value1rev2Proj1)
      )
    }
    "cache projection for view" in {
      val projectionId =
        ElasticSearchViews.projectionId(views.fetch(viewId, project1.ref).accepted.asInstanceOf[IndexingViewResource])
      cache.get(projectionId).accepted.value shouldEqual ProjectionProgress(Sequence(6), Instant.EPOCH, 4, 1, 0, 0)
    }
    "index resources with type" in {
      val indexVal =
        indexingValue.copy(pipeline =
          List(FilterByType(Set(type1))) ++ indexingValue.pipeline.filterNot(
            _ == FilterDeprecated()
          )
        )
      val view     = views.update(viewId, project1.ref, 4L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(view, documentFor(res3Proj1, value3Proj1))
    }
    "index resources without source" in {
      val indexVal = indexingValue.copy(pipeline = indexingValue.pipeline.filterNot(_ == SourceAsText()))
      val view     = views.update(viewId, project1.ref, 5L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkElasticSearchDocuments(
        view,
        documentWithSourceAsJsonFor(res2Proj1, value2Proj1),
        documentWithSourceAsJsonFor(res1rev2Proj1, value1rev2Proj1)
      )
    }
  }

  private def checkElasticSearchDocuments(view: IndexingViewResource, expected: JsonObject*) = {
    eventually {
      val index = IndexLabel.unsafe(ElasticSearchViews.index(view, externalCfg))
      listAll(index).sources shouldEqual expected
    }

    if (view.rev > 1L) {
      val previous =
        views.fetch(IdSegmentRef(view.id, view.rev - 1), view.value.project).accepted.asInstanceOf[IndexingViewResource]
      eventually {
        val index = IndexLabel.unsafe(ElasticSearchViews.index(previous, externalCfg))
        esClient.existsIndex(index).accepted shouldEqual false
      }
    }

  }

  private def documentWithMetaFor(resource: EventExchangeValue[_, _], intValue: Int, uuid: UUID) =
    documentFor(resource, intValue) deepMerge
      resource.value.resource.void.toCompactedJsonLd.accepted.json.asObject.value.remove(keywords.context) deepMerge
      JsonObject("_uuid" -> uuid.asJson)

  private def documentWithSourceAsJsonFor(resource: EventExchangeValue[_, _], intValue: Int)     =
    resource.value.source.asObject.value.removeAllKeys(keywords.context) deepMerge
      documentFor(resource, intValue).remove("_original_source").remove("@type")

  private def documentFor(resource: EventExchangeValue[_, _], intValue: Int) = {
    val types     = resource.value.resource.types
    val typesJson = if (types.size == 1) types.head.asJson else types.asJson
    JsonObject(
      keywords.id        -> resource.value.resource.id.asJson,
      "_original_source" -> resource.value.source.noSpaces.asJson,
      "@type"            -> typesJson,
      "prefLabel"        -> s"name-$intValue".asJson
    )
  }

  private def exchangeValue(
      id: Iri,
      project: ProjectRef,
      tpe: Iri,
      deprecated: Boolean,
      schema: Iri,
      value: Int,
      uuid: UUID
  )(implicit
      caller: Caller
  ): EventExchangeValue[Value, Metadata] = {
    val resource = ResourceF(
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
      Value(id, value)
    )
    val metadata = JsonLdValue(Metadata(uuid))
    EventExchangeValue(ReferenceExchangeValue(resource, resource.value.asJson, Value.jsonLdEncoderValue), metadata)
  }

}

object ElasticSearchIndexingSpec extends CirceLiteral {
  final case class Value(id: Iri, number: Int)
  object Value {
    private val ctx                                       = ContextValue(
      json"""{"@vocab": "https://bluebrain.github.io/nexus/vocabulary/", "label": "http://www.w3.org/2004/02/skos/core#prefLabel"}"""
    )
    implicit val encoderValue: Encoder.AsObject[Value]    = Encoder.encodeJsonObject.contramapObject {
      case Value(id, number) => jobj"""{"@id": "$id", "label": "name-$number", "number": $number}"""
    }
    implicit val jsonLdEncoderValue: JsonLdEncoder[Value] = JsonLdEncoder.computeFromCirce(ctx)
  }

  final case class Metadata(uuid: UUID)
  object Metadata {
    private val ctx                                                  = ContextValue(json"""{"@vocab": "https://bluebrain.github.io/nexus/vocabulary/"}""")
    implicit private val encoderMetadata: Encoder.AsObject[Metadata] = deriveEncoder
    implicit val jsonLdEncoderMetadata: JsonLdEncoder[Metadata]      = JsonLdEncoder.computeFromCirce(ctx)

  }
}
