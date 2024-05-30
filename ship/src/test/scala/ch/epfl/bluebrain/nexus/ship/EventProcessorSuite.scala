package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.PullRequestCreated
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder}

import java.time.Instant

class EventProcessorSuite extends NexusSuite {

  implicit private val eventDecoder: Codec[PullRequestEvent] = PullRequest.PullRequestEvent.serializer.codec

  private def createEventProcessor(ref: Ref[IO, Option[Iri]]) = new EventProcessor[PullRequestEvent] {
    override def resourceType: EntityType = PullRequest.entityType

    override def decoder: Decoder[PullRequestEvent] = eventDecoder

    override def evaluate(event: PullRequestEvent): IO[ImportStatus] = ref.set(Some(event.id)).as(ImportStatus.Success)
  }

  test("Replace the original id in the value and pass it down") {
    implicit val iriPatcher     = IriPatcher(iri"https://bbp.epfl.ch/", iri"https://openbrainplatform.com/")
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

}
