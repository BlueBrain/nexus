package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourceInstanceFixture
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.PullRequestCreated
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Codec, Decoder, Json}

import java.time.Instant

final case class ExampleEvent(
    id: Iri,
    project: ProjectRef,
    rev: Int,
    instant: Instant,
    subject: Identity.Subject,
    source: Json
) extends ScopedEvent

class EventProcessorSuite extends NexusSuite with ResourceInstanceFixture {

  import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
  implicit private val configuration: Configuration                    = Configuration.default.withDiscriminator("@type")
  implicit private val eventDecoder: Codec[PullRequestEvent]           = PullRequest.PullRequestEvent.serializer.codec
  implicit private val exampleEventCodec: Codec.AsObject[ExampleEvent] = deriveConfiguredCodec[ExampleEvent]

  private def createEventProcessor(ref: Ref[IO, Option[Iri]]) = new EventProcessor[PullRequestEvent] {
    override def resourceType: EntityType = PullRequest.entityType

    override def decoder: Decoder[PullRequestEvent] = eventDecoder

    override def evaluate(event: PullRequestEvent): IO[ImportStatus] = ref.set(Some(event.id)).as(ImportStatus.Success)
  }

  private def exampleEventProcessor(ref: Ref[IO, Option[ExampleEvent]]) = new EventProcessor[ExampleEvent] {
    override def resourceType: EntityType                        = EntityType("example")
    override def decoder: Decoder[ExampleEvent]                  = exampleEventCodec
    override def evaluate(event: ExampleEvent): IO[ImportStatus] = ref.set(Some(event)).as(ImportStatus.Success)
  }

  test("Replace the original id in the value and pass it down") {
    implicit val iriPatcher     = IriPatcher(iri"https://bbp.epfl.ch/", iri"https://openbrainplatform.com/", Map.empty)
    val project                 = ProjectRef.unsafe("org", "proj")
    val originalId              = iri"https://bbp.epfl.ch/pr1"
    val expectedId              = iri"https://openbrainplatform.com/pr1"
    val event: PullRequestEvent = PullRequestCreated(originalId, project, Instant.EPOCH, Anonymous)
    val rowEvent                = RowEvent(
      Offset.At(42L),
      PullRequest.entityType,
      project.organization,
      project.project,
      originalId,
      1,
      event.asJson,
      Instant.EPOCH
    )
    for {
      ref           <- Ref.of[IO, Option[Iri]](None)
      eventProcessor = createEventProcessor(ref)
      _             <- eventProcessor.evaluate(rowEvent)
      _             <- ref.get.assertEquals(Some(expectedId))
    } yield ()
  }

  test("Replace the original id in the value and pass it down 2") {
    implicit val iriPatcher = IriPatcher.noop
    val project             = ProjectRef.unsafe("org", "proj")
    val id                  = iri"https://bbp.epfl.ch/resource1"
    val event               = ExampleEvent(
      id,
      project,
      1,
      Instant.EPOCH,
      Anonymous,
      Json.obj("_instant" := Instant.now(), "normalField" := "value")
    )
    val rowEvent            = RowEvent(
      Offset.At(42L),
      PullRequest.entityType,
      project.organization,
      project.project,
      id,
      1,
      event.asJson.deepMerge(Json.obj()),
      Instant.EPOCH
    )
    for {
      ref           <- Ref.of[IO, Option[ExampleEvent]](None)
      eventProcessor = exampleEventProcessor(ref)
      _             <- eventProcessor.evaluate(rowEvent)
      _             <- ref.get.assert(_.exists(_.source == Json.obj("normalField" := "value")))
    } yield ()
  }

}
