package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingSpec.Value
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{permissions, _}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSourceDummy, IndexingStreamController}
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Encoder
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{DoNotDiscover, EitherValues, Inspectors}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._
/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 160
}
 */
@DoNotDiscover
class BlazegraphIndexingSpec
    extends AbstractDBSpec
    with EitherValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with TestMatchers
    with ConfigFixtures
    with Eventually
    with Fixtures {

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
    permissions.query
  )

  private val org      = Label.unsafe("org")
  private val base     = nxv.base
  private val project1 = ProjectGen.project("org", "proj", base = base)
  private val project2 = ProjectGen.project("org", "proj2", base = base)

  private val (orgs, projs) = ProjectSetup.init(org :: Nil, project1 :: project2 :: Nil).accepted

  implicit private val kvCfg: KeyValueStoreConfig          = keyValueStore
  implicit private val externalCfg: ExternalIndexingConfig = externalIndexing

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
    ).zipWithIndex.foldLeft(Map.empty[ProjectRef, Seq[Message[EventExchangeValue[_, _]]]]) { case (acc, ((res, project), i)) =>
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

  implicit private val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
  private val httpClient          = HttpClient()
  private val blazegraphClient    = BlazegraphClient(httpClient, blazegraphHostConfig.endpoint, None, 10.seconds)
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

  implicit private val patience: PatienceConfig =
    PatienceConfig(15.seconds, Span(1000, Millis))

  private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

  private def nTriplesFrom(index: String): NTriples =
    blazegraphClient
      .query(Set(index), SparqlQuery("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o} ORDER BY ?s"), SparqlNTriples)
      .accepted
      .value

  private val config = BlazegraphViewsSetup.config

  private val indexingStream = new BlazegraphIndexingStream(blazegraphClient, indexingSource, cache, config, projection)

  private val views: BlazegraphViews = BlazegraphViewsSetup.init(orgs, projs, permissions.query)
  private val indexingCleanup        = new BlazegraphIndexingCleanup(blazegraphClient, cache, projection)
  private val controller             = new IndexingStreamController[IndexingBlazegraphView](BlazegraphViews.moduleType)
  BlazegraphIndexingCoordinator(views, controller, indexingStream, indexingCleanup, config).accepted

  "BlazegraphIndexing" should {

    "index resources for project1" in {
      val view = views.create(viewId, project1.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(
        view,
        triplesFor(res2Proj1, value2Proj1),
        triplesFor(res1rev2Proj1, value1rev2Proj1)
      )
    }
    "index resources for project2" in {
      val view = views.create(viewId, project2.ref, indexingValue).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(
        view,
        triplesFor(res1Proj2, value1Proj2),
        triplesFor(res2Proj2, value2Proj2)
      )
    }
    "index resources with metadata" in {
      val indexVal     = indexingValue.copy(includeMetadata = true)
      val project1View = views.update(viewId, project1.ref, 1L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(
        project1View,
        triplesWithMetadataFor(res2Proj1, value2Proj1, project1.ref),
        triplesWithMetadataFor(res1rev2Proj1, value1rev2Proj1, project1.ref)
      )
    }
    "index resources including deprecated" in {
      val indexVal = indexingValue.copy(includeDeprecated = true)
      val view     = views.update(viewId, project1.ref, 2L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(
        view,
        triplesFor(res2Proj1, value2Proj1),
        triplesFor(res3Proj1, value3Proj1),
        triplesFor(res1rev2Proj1, value1rev2Proj1)
      )
    }
    "index resources constrained by schema" in {
      val indexVal = indexingValue.copy(includeDeprecated = true, resourceSchemas = Set(schema1))
      val view     = views.update(viewId, project1.ref, 3L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(
        view,
        triplesFor(res3Proj1, value3Proj1),
        triplesFor(res1rev2Proj1, value1rev2Proj1)
      )
    }

    "cache projection for view" in {
      val projectionId = BlazegraphViews.projectionId(views.fetchIndexingView(viewId, project1.ref).accepted)
      cache.get(projectionId).accepted.value shouldEqual ProjectionProgress(Sequence(6), Instant.EPOCH, 4, 1, 0, 0)
    }

    "index resources with type" in {
      val indexVal = indexingValue.copy(includeDeprecated = true, resourceTypes = Set(type1))
      val view     = views.update(viewId, project1.ref, 4L, indexVal).accepted.asInstanceOf[IndexingViewResource]
      checkBlazegraphTriples(view, triplesFor(res3Proj1, value3Proj1))
    }

  }

  private def checkBlazegraphTriples(view: IndexingViewResource, expected: NTriples*) = {
    eventually {
      val index = BlazegraphViews.namespace(view, externalCfg)
      nTriplesFrom(index).value should equalLinesUnordered(expected.foldLeft(NTriples.empty)(_ ++ _).value)
    }
    if (view.rev > 1L) {
      val previous =
        views.fetch(IdSegmentRef(view.id, view.rev - 1), view.value.project).accepted.asInstanceOf[IndexingViewResource]
      eventually {
        val index = BlazegraphViews.namespace(previous, externalCfg)
        blazegraphClient.existsNamespace(index).accepted shouldEqual false
      }
    }
  }

  def triplesFor(resource: EventExchangeValue[_, _], intValue: Int): NTriples =
    NTriples(
      s"""
         |${resource.value.resource.resolvedId.rdfFormat} ${(nxv + "bool").rdfFormat} "false"^^${xsd.boolean.rdfFormat} .
         |${resource.value.resource.resolvedId.rdfFormat} ${(nxv + "number").rdfFormat} "$intValue"^^${xsd.integer.rdfFormat} .
         |${resource.value.resource.resolvedId.rdfFormat} ${rdf.tpe.rdfFormat} ${resource.value.resource.types.head.rdfFormat} .
         |""".stripMargin,
      resource.value.resource.resolvedId
    )

  def triplesWithMetadataFor(
      resource: EventExchangeValue[_, _],
      intValue: Int,
      project: ProjectRef
  ): NTriples = {
    val res = resource.value.resource
    triplesFor(resource, intValue) ++ NTriples(
      s"""
         |${res.resolvedId.rdfFormat} ${nxv.rev.iri.rdfFormat} "${res.rev}"^^${xsd.integer.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.deprecated.iri.rdfFormat} "${res.deprecated}"^^${xsd.boolean.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.createdAt.iri.rdfFormat} "${res.createdAt
        .atOffset(ZoneOffset.UTC)
        .format(dateTimeFormatter)}"^^${xsd.dateTime.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.updatedAt.iri.rdfFormat} "${res.updatedAt
        .atOffset(ZoneOffset.UTC)
        .format(dateTimeFormatter)}"^^${xsd.dateTime.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.createdBy.iri.rdfFormat} ${res.createdBy.id.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.updatedBy.iri.rdfFormat} ${res.updatedBy.id.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.constrainedBy.iri.rdfFormat} ${res.schema.iri.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.project.iri.rdfFormat} ${ResourceUris.project(project).accessUri.toIri.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.self.iri.rdfFormat} ${res.uris.accessUri.toIri.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.incoming.iri.rdfFormat} ${(res.uris.accessUri / "incoming").toIri.rdfFormat} .
         |${res.resolvedId.rdfFormat} ${nxv.outgoing.iri.rdfFormat} ${(res.uris.accessUri / "outgoing").toIri.rdfFormat} .
         |""".stripMargin,
      res.resolvedId
    )
  }

  private def exchangeValue(id: Iri, project: ProjectRef, tpe: Iri, deprecated: Boolean, schema: Iri, value: Int)(implicit
      caller: Caller
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
    private val ctx = ContextValue(json"""{"@vocab": "https://bluebrain.github.io/nexus/vocabulary/"}""")
    implicit val encoderValue: Encoder.AsObject[Value]    = Encoder.encodeJsonObject.contramapObject { case Value(id, tpe, number) =>
      jobj"""{"@id": "$id", "@type": "$tpe", "bool": false, "number": $number}"""
    }
    implicit val jsonLdEncoderValue: JsonLdEncoder[Value] = JsonLdEncoder.computeFromCirce(ctx)
  }
}
