package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingSpec.Value
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingSourceDummy
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import io.circe.Encoder
import io.circe.syntax._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{DoNotDiscover, EitherValues, Inspectors}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphIndexingSpec
    extends AbstractDBSpec
    with EitherValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
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

  private val indexingValue = IndexingBlazegraphViewValue(
    Set.empty,
    Set.empty,
    None,
    includeMetadata = false,
    includeDeprecated = false,
    defaultPermission
  )

  private val allowedPerms = Set(defaultPermission)

  private val perms    = PermissionsDummy(allowedPerms).accepted
  private val org      = Label.unsafe("org")
  private val base     = nxv.base
  private val project1 = ProjectGen.project("org", "proj", base = base)
  private val project2 = ProjectGen.project("org", "proj2", base = base)

  private def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil
      )

  private val config = BlazegraphViewsConfig(
    "http://localhost",
    None,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing
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

  private val schema1 = idPrefix / "Schema1"
  private val schema2 = idPrefix / "Schema2"

  private val type1 = idPrefix / "Type1"
  private val type2 = idPrefix / "Type2"
  private val type3 = idPrefix / "Type3"

  private val res1Proj1     = exchangeValue(id1Proj1, project1.ref, type1, false, schema1, value1Proj1)
  private val res2Proj1     = exchangeValue(id2Proj1, project1.ref, type2, false, schema2, value2Proj1)
  private val res3Proj1     = exchangeValue(id3Proj1, project1.ref, type1, true, schema1, value3Proj1)
  private val res1Proj2     = exchangeValue(id1Proj2, project2.ref, type1, false, schema1, value1Proj2)
  private val res2Proj2     = exchangeValue(id2Proj2, project2.ref, type2, false, schema2, value2Proj2)
  private val res3Proj2     = exchangeValue(id3Proj2, project2.ref, type1, true, schema2, value3Proj2)
  private val res1rev2Proj1 = exchangeValue(id1Proj1, project1.ref, type3, false, schema1, value1rev2Proj1)

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
          res.value.toResource.id.toString,
          i.toLong,
          res,
          Vector.empty
        )
        acc.updatedWith(project)(seqOpt => Some(seqOpt.getOrElse(Seq.empty) :+ entry))
    }

  private val indexingSource = new IndexingSourceDummy(messages.map { case (k, v) => (k, None) -> v })

  implicit private val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  private val httpClient          = HttpClient()
  private val blazegraphClient    = BlazegraphClient(httpClient, blazegraphHostConfig.endpoint, None)
  private val projection          = Projection.inMemory(()).accepted

  private val cache: KeyValueStore[ProjectionId, ProjectionProgress[Unit]] =
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "BlazegraphViewsProgress",
      (_, progress) =>
        progress.offset match {
          case Sequence(v) => v
          case _           => 0L
        }
    )

  implicit private val patience: PatienceConfig                         =
    PatienceConfig(15.seconds, Span(1000, Millis))
  implicit private val bindingsOrdering: Ordering[Map[String, Binding]] =
    Ordering.by(map => s"${map.keys.toSeq.sorted.mkString}${map.values.map(_.value).toSeq.sorted.mkString}")

  private def selectAllFrom(index: String): SparqlResults =
    blazegraphClient.query(Set(index), SparqlQuery("SELECT * WHERE {?s ?p ?o} ORDER BY ?s")).accepted

  private val resolverContext: ResolverContextResolution =
    new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))

  private val indexingStream = new BlazegraphIndexingStream(blazegraphClient, indexingSource, cache, config, projection)

  private val views: BlazegraphViews = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- projectSetup
    views            <- BlazegraphViews(config, eventLog, resolverContext, perms, orgs, projects)
    _                <- BlazegraphIndexingCoordinator(views, indexingStream, config)

  } yield views).accepted

  "BlazegraphIndexing" should {

    "index resources for project1" in {
      val project1View     = views.create(viewId, project1.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project1View, externalCfg)
      val expectedBindings =
        List(bindingsFor(res2Proj1, value2Proj1), bindingsFor(res1rev2Proj1, value1rev2Proj1)).flatten
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources for project2" in {
      val project2View     = views.create(viewId, project2.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project2View, externalCfg)
      val expectedBindings =
        List(bindingsFor(res1Proj2, value1Proj2), bindingsFor(res2Proj2, value2Proj2)).flatten
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources with metadata" in {
      val indexVal         = indexingValue.copy(includeMetadata = true)
      val project1View     = views.update(viewId, project1.ref, 1L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project1View, externalCfg)
      val expectedBindings =
        List(
          bindingsWithMetadataFor(res2Proj1, value2Proj1, project1.ref),
          bindingsWithMetadataFor(res1rev2Proj1, value1rev2Proj1, project1.ref)
        ).flatten
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }

    }
    "index resources including deprecated" in {
      val indexVal         = indexingValue.copy(includeDeprecated = true)
      val project1View     = views.update(viewId, project1.ref, 2L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project1View, externalCfg)
      val expectedBindings =
        List(
          bindingsFor(res2Proj1, value2Proj1),
          bindingsFor(res3Proj1, value3Proj1),
          bindingsFor(res1rev2Proj1, value1rev2Proj1)
        ).flatten
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources constrained by schema" in {
      val indexVal         = indexingValue.copy(includeDeprecated = true, resourceSchemas = Set(schema1))
      val project1View     = views.update(viewId, project1.ref, 3L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project1View, externalCfg)
      val expectedBindings =
        List(
          bindingsFor(res3Proj1, value3Proj1),
          bindingsFor(res1rev2Proj1, value1rev2Proj1)
        ).flatten
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }

    "cache projection for view" in {
      val projectionId = BlazegraphViews.projectionId(views.fetchIndexingView(viewId, project1.ref).accepted)
      cache.get(projectionId).accepted.value shouldEqual ProjectionProgress(Sequence(6), Instant.EPOCH, 4, 1, 0, 0)
    }

    "index resources with type" in {
      val indexVal         = indexingValue.copy(includeDeprecated = true, resourceTypes = Set(type1))
      val project1View     = views.update(viewId, project1.ref, 4L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      val index            = BlazegraphViews.index(project1View, externalCfg)
      val expectedBindings = bindingsFor(res3Proj1, value3Proj1)
      eventually {
        selectAllFrom(index).results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }

  }

  def bindingsFor(resource: EventExchangeValue[_, _], intValue: Int): List[Map[String, Binding]] =
    List(
      Map(
        "s" -> Binding("uri", resource.value.toResource.id.toString),
        "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/bool"),
        "o" -> Binding("literal", "false", None, Some("http://www.w3.org/2001/XMLSchema#boolean"))
      ),
      Map(
        "s" -> Binding("uri", resource.value.toResource.id.toString),
        "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/number"),
        "o" -> Binding("literal", intValue.toString, None, Some("http://www.w3.org/2001/XMLSchema#integer"))
      ),
      Map(
        "s" -> Binding("uri", resource.value.toResource.id.toString),
        "p" -> Binding("uri", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        "o" -> Binding("uri", resource.value.toResource.types.head.toString)
      )
    )

  def bindingsWithMetadataFor(
      resource: EventExchangeValue[_, _],
      intValue: Int,
      project: ProjectRef
  ): List[Map[String, Binding]] = {
    val res                         = resource.value.toResource
    val blazegraphDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    bindingsFor(resource, intValue) ++ List(
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.rev.iri.toString),
        "o" -> Binding("literal", res.rev.toString, None, Some("http://www.w3.org/2001/XMLSchema#integer"))
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.deprecated.iri.toString),
        "o" -> Binding("literal", res.deprecated.toString, None, Some("http://www.w3.org/2001/XMLSchema#boolean"))
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.createdAt.iri.toString),
        "o" -> Binding(
          "literal",
          res.createdAt.atOffset(ZoneOffset.UTC).format(blazegraphDateTimeFormatter),
          None,
          Some("http://www.w3.org/2001/XMLSchema#dateTime")
        )
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.updatedAt.iri.toString),
        "o" -> Binding(
          "literal",
          res.updatedAt
            .atOffset(ZoneOffset.UTC)
            .format(blazegraphDateTimeFormatter),
          None,
          Some("http://www.w3.org/2001/XMLSchema#dateTime")
        )
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.createdBy.iri.toString),
        "o" -> Binding("uri", res.createdBy.id.toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.updatedBy.iri.toString),
        "o" -> Binding("uri", res.updatedBy.id.toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.constrainedBy.iri.toString),
        "o" -> Binding("uri", res.schema.iri.toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.project.iri.toString),
        "o" -> Binding("uri", ResourceUris.project(project).accessUri.toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.self.iri.toString),
        "o" -> Binding("uri", res.uris.accessUri.toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.incoming.iri.toString),
        "o" -> Binding("uri", (res.uris.accessUri / "incoming").toString)
      ),
      Map(
        "s" -> Binding("uri", res.id.toString),
        "p" -> Binding("uri", nxv.outgoing.iri.toString),
        "o" -> Binding("uri", (res.uris.accessUri / "outgoing").toString)
      )
    )
  }

  private def exchangeValue(id: Iri, project: ProjectRef, tpe: Iri, deprecated: Boolean, schema: Iri, value: Int)(
      implicit caller: Caller
  ) = {
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
      Value(id, tpe, value)
    )
    val metadata = JsonLdValue(())
    EventExchangeValue(ReferenceExchangeValue(resource, resource.value.asJson, Value.jsonLdEncoderValue), metadata)
  }

}

object BlazegraphIndexingSpec extends CirceLiteral {
  final case class Value(id: Iri, tpe: Iri, number: Int)
  object Value {
    private val ctx                                       = ContextValue(json"""{"@vocab": "https://bluebrain.github.io/nexus/vocabulary/"}""")
    implicit val encoderValue: Encoder.AsObject[Value]    = Encoder.encodeJsonObject.contramapObject {
      case Value(id, tpe, number) => jobj"""{"@id": "$id", "@type": "$tpe", "bool": false, "number": $number}"""
    }
    implicit val jsonLdEncoderValue: JsonLdEncoder[Value] = JsonLdEncoder.computeFromCirce(ctx)
  }
}
