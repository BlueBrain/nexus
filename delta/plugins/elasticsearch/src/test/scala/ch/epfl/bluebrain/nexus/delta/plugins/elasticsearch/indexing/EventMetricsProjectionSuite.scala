package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{EventMetricsProjection, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.{Created, ProjectScopedMetric, Updated}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CacheSink, ProjectionProgress, SupervisorSetup}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class EventMetricsProjectionSuite extends BioSuite with SupervisorSetup.Fixture with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(2.seconds, 10.millis)

  private lazy val (sv, _) = supervisor()
  private val sink         = new CacheSink[Json]

  private val metric1 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Created,
    ProjectRef.unsafe("org", "project"),
    Label.unsafe("org"),
    iri"http://bbp.epfl.ch/1",
    Set(iri"Entity"),
    JsonObject.empty
  )
  private val metric2 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Updated,
    ProjectRef.unsafe("org", "project"),
    Label.unsafe("org"),
    iri"http://bbp.epfl.ch/2",
    Set(iri"Entity"),
    JsonObject.empty
  )

  private val envelopes = List(
    Envelope(EntityType("entity"), "first", 1, metric1, Instant.EPOCH, Offset.At(1L)),
    Envelope(EntityType("entity"), "second", 1, metric2, Instant.EPOCH, Offset.At(2L))
  )

  test("Start the metrics projection") {

    for {
      _       <- EventMetricsProjection(
                   sink,
                   sv,
                   _ => Stream.emits(envelopes),
                   Task.unit
                 )
      running <- sv.getRunningProjections()
      _        = println(running)
      _       <- sv.describe(EventMetricsProjection.projectionMetadata.name)
                   .map(_.map(_.progress))
                   .eventuallySome(ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 0, 0))
    } yield ()

  }

}
