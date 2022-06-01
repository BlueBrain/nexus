package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metric

import akka.http.scaladsl.model.Uri.Query
import akka.persistence.query.{NoOffset, Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.QueryBuilder
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metric.ProjectEventMetricsStreamSpec.{SimpleEvent, SimpleEventExchange}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import fs2.Stream
import io.circe.JsonObject
import monix.bio.{Task, UIO}
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.{Duration, Instant}
import scala.concurrent.duration._

@DoNotDiscover
class ProjectEventMetricsStreamSpec(override val docker: ElasticSearchDocker)
    extends AbstractDBSpec
    with AnyWordSpecLike
    with Matchers
    with ElasticSearchClientSetup
    with ConfigFixtures
    with Eventually
    with IOValues {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val uuidF: UUIDF = UUIDF.random

  private val project          = ProjectRef.unsafe("org", "proj")
  private val instant          = Instant.now()
  private val realm: Label     = Label.unsafe("realm")
  private val subject: Subject = User("username", realm)

  private def incDays(days: Int) = instant.plus(Duration.ofDays(days.toLong))

  val events: Seq[ProjectScopedEvent] = List(
    SimpleEvent(
      nxv + "s1",
      Set(nxv + "Type1"),
      project,
      1L,
      instant,
      subject
    ),
    SimpleEvent(
      nxv + "s2",
      Set(nxv + "Type2", nxv + "Type4"),
      project,
      1L,
      incDays(1),
      subject
    ),
    SimpleEvent(
      nxv + "s3",
      Set(nxv + "Type3"),
      project,
      1L,
      incDays(2),
      subject
    ),
    SimpleEvent(
      nxv + "s4",
      Set(nxv + "Type1", nxv + "Type3"),
      project,
      1L,
      incDays(3),
      subject
    ),
    SimpleEvent(
      nxv + "s5",
      Set(nxv + "Type4", nxv + "Type5"),
      project,
      1L,
      incDays(4),
      subject
    )
  )

  private val eventStream: Stream[Task, Envelope[ProjectScopedEvent]] = Stream
    .emits(
      events.zipWithIndex.map { case (event, i) =>
        Envelope(event, Sequence(i.toLong), s"simple-$i", i.toLong)
      }
    )
    .covary[Task]

  private def stream(offset: Offset): Stream[Task, Envelope[ProjectScopedEvent]] =
    offset match {
      case NoOffset        => eventStream
      case Sequence(value) => eventStream.take(value)
      case _               => throw new IllegalArgumentException("")
    }

  private val projection = Projection.inMemory(()).accepted

  "Metrics" should {

    "retrieve its offset" in eventually {
      val currentProgress = projection.progress(ProjectEventMetricsStream.projectionId).accepted
      currentProgress shouldEqual ProjectionProgress(Sequence(4L), incDays(4), 5L, 0L, 0L, 0L, ())
    }

    "not give any errors" in eventually {
      projection.errors(ProjectEventMetricsStream.projectionId).compile.toList.accepted shouldEqual List.empty
    }

    "index correctly events" in eventually {
      val results = esClient
        .search(
          QueryBuilder.empty.withSort(SortList(List(Sort("instant")))),
          Set("prefix_project_metrics"),
          Query.Empty
        )
        .accepted
        .sources
      results.size shouldEqual 5
    }

  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val _ = ProjectEventMetricsStream(stream, Set(SimpleEventExchange), esClient, projection, externalIndexing).accepted
  }
}

object ProjectEventMetricsStreamSpec {

  final case class SimpleEvent(
      id: Iri,
      types: Set[Iri],
      project: ProjectRef,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectScopedEvent

  sealed trait NoOp

  object SimpleEventExchange extends EventExchange {
    override type E = SimpleEvent
    override type A = NoOp
    override type M = NoOp

    override def toJsonEvent(event: Event): Option[Aux[SimpleEvent]] = None

    override def toMetric(event: Event): UIO[Option[EventMetric]] = event match {
      case s: SimpleEvent =>
        UIO.some(
          ProjectScopedMetric.from[SimpleEvent](
            s,
            EventMetric.Created,
            s.id,
            s.types,
            JsonObject.empty
          )
        )
      case _              => UIO.none
    }

    override def toResource(
        event: Event,
        tag: Option[UserTag]
    ): UIO[Option[EventExchange.EventExchangeValue[NoOp, NoOp]]] = UIO.none
  }

}
