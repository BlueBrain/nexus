package ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.Sequence
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.RelationshipResolution
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.RelationshipResolution.Relationship
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing.StatisticsIndexingStreamSpec.Value
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingSourceDummy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral._
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class StatisticsIndexingStreamSpec
    extends TestKit(ActorSystem("StatisticsIndexingStreamSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with IOFixedClock
    with ConfigFixtures
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(10.seconds, Span(10, Millis))

  "StatisticsIndexingStream" should {

    implicit val caller: Caller        = Caller.Anonymous
    implicit val sc: Scheduler         = Scheduler.global
    implicit val cfg: HttpClientConfig =
      HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
    implicit val jsonLdApi: JsonLdApi  = JsonLdJavaApi.lenient

    val endpoint = elasticsearchHost.endpoint
    val client   = new ElasticSearchClient(HttpClient(), endpoint, 2000)
    val project  = ProjectRef.unsafe("myorg", "myproject")
    def exchangeValue(value: Value)(implicit caller: Caller): EventExchangeValue[Value, Unit] = {
      val resource = ResourceF(
        value.id,
        ResourceUris.apply("resources", project, value.id)(
          Resources.mappings,
          ProjectBase.unsafe(iri"http://example.com/")
        ),
        1L,
        Set(schema.Person),
        deprecated = false,
        Instant.EPOCH,
        caller.subject,
        Instant.EPOCH,
        caller.subject,
        Latest(schemas.resources),
        value
      )
      EventExchangeValue(
        ReferenceExchangeValue(resource, resource.value.asJson, Value.jsonLdEncoderValue),
        JsonLdValue(())
      )
    }

    val robert = exchangeValue(Value(iri"http://example.com/Robert", iri"http://example.com/Sam", "Rue 1"))
    val anna   = exchangeValue(Value(iri"http://example.com/Anna", iri"http://example.com/Robert", "Rue 2"))
    val sam    = exchangeValue(Value(iri"http://example.com/Sam", iri"http://example.com/Pedro", "Rue 3"))

    val indexingSource = new IndexingSourceDummy(
      Seq(robert, anna, sam).zipWithIndex
        .foldLeft(Map.empty[ProjectRef, Seq[Message[EventExchangeValue[_, _]]]]) { case (acc, (res, i)) =>
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
        .map { case (k, v) => (k, None) -> v }
    )

    val projection: Projection[Unit] = Projection.inMemory(()).accepted
    val progressesCache              = KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted

    val relationshipResolution = new RelationshipResolution {
      override def apply(projectRef: ProjectRef, id: Iri): UIO[Option[Relationship]] =
        if (projectRef == project)
          UIO.pure(Seq(robert, anna, sam).collectFirst {
            case person if person.value.resource.id == id => Relationship(person.value.resource.id, Set(schema.Person))
          })
        else IO.none
    }

    implicit val rcr = RemoteContextResolution.never
    val mapping      = jsonObjectContentOf("elasticsearch/mappings.json")
    val stream       = new StatisticsIndexingStream(
      client,
      indexingSource,
      progressesCache,
      externalIndexing,
      projection,
      relationshipResolution
    )

    "generate statistics index with statistics data" in {
      val viewIndex = ViewIndex(
        project,
        iri"",
        UUID.randomUUID(),
        ViewProjectionId("stats"),
        "idx",
        1,
        false,
        None,
        Instant.EPOCH,
        StatisticsView(mapping)
      )
      stream(viewIndex, ProgressStrategy.FullRestart).take(3).compile.toList.accepted
      eventually {
        client
          .search(JsonObject.empty, Set("idx"), Uri.Query.Empty)()
          .accepted
          .removeKeys("took", "_shards") shouldEqual
          jsonContentOf("statistics-indexed-documents.json")
      }
    }
  }

}

object StatisticsIndexingStreamSpec {
  final case class Value(id: Iri, brother: Iri, street: String)
  object Value {
    private val ctx                                       = ContextValue(
      json"""{"@base": "http://example.com/", "@vocab": "http://schema.org/", "brother": {"@type": "@id"}}"""
    )
    implicit val encoderValue: Encoder.AsObject[Value]    = Encoder.encodeJsonObject.contramapObject {
      case Value(id, brother, street) =>
        jobj"""{"@id": "$id", "givenName": "${id.lastSegment}", "brother": "$brother", "address": {"street": "$street"}}"""
    }
    implicit val jsonLdEncoderValue: JsonLdEncoder[Value] = JsonLdEncoder.computeFromCirce(ctx)
  }
}
