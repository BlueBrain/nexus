package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.{ProjectScopedMetric, _}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.Stream
import io.circe.{Json, JsonObject}

import java.time.Instant

object MetricsStream {

  private val org             = Label.unsafe("org")
  private val proj1           = Label.unsafe("proj1")
  private val proj2           = Label.unsafe("proj2")
  val projectRef1: ProjectRef = ProjectRef(org, proj1)
  val projectRef2: ProjectRef = ProjectRef(org, proj2)

  val metric1: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Created,
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(iri"File"),
    JsonObject.apply(
      "storage"        -> Json.fromString("storageId"),
      "newFileWritten" -> Json.fromInt(1),
      "bytes"          -> Json.fromLong(10L)
    )
  )
  val metric2: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    2,
    Updated,
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(iri"File"),
    JsonObject.apply(
      "storage"        -> Json.fromString("storageId"),
      "newFileWritten" -> Json.fromInt(1),
      "bytes"          -> Json.fromLong(20L)
    )
  )
  val metric3: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    3,
    Tagged,
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(iri"File"),
    JsonObject.apply(
      "storage" -> Json.fromString("storageId")
    )
  )
  val metric4: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    4,
    TagDeleted,
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(iri"File"),
    JsonObject.apply(
      "storage" -> Json.fromString("storageId")
    )
  )
  val metric5: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Created,
    projectRef2,
    org,
    iri"http://bbp.epfl.ch/file2",
    Set(iri"File"),
    JsonObject.apply(
      "storage"        -> Json.fromString("storageId"),
      "newFileWritten" -> Json.fromInt(1),
      "bytes"          -> Json.fromLong(20L)
    )
  )
  val metric6: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    2,
    Deprecated,
    projectRef2,
    org,
    iri"http://bbp.epfl.ch/file2",
    Set(iri"File"),
    JsonObject.apply(
      "storage" -> Json.fromString("storageId")
    )
  )

  private val envelopes = List(
    // Create file in proj1
    Envelope(EntityType("entity"), nxv + "1", 1, metric1, Instant.EPOCH, Offset.At(1L)),
    // Update file in proj1
    Envelope(EntityType("entity"), nxv + "2", 1, metric2, Instant.EPOCH, Offset.At(2L)),
    // Tag file in proj1
    Envelope(EntityType("entity"), nxv + "3", 1, metric3, Instant.EPOCH, Offset.At(3L)),
    // Delete file tag in proj 1
    Envelope(EntityType("entity"), nxv + "4", 1, metric4, Instant.EPOCH, Offset.At(4L)),
    // Create file in proj 2
    Envelope(EntityType("entity"), nxv + "5", 1, metric5, Instant.EPOCH, Offset.At(5L)),
    // Deprecate file in proj 2
    Envelope(EntityType("entity"), nxv + "6", 1, metric6, Instant.EPOCH, Offset.At(6L))
  )

  val metricsStream: Stream[IO, Envelope[ProjectScopedMetric]] =
    Stream.emits(envelopes)

}
