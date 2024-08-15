package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.{ProjectScopedMetric, _}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
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
    Set(Created),
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
    Set(Updated),
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
    Set(Tagged),
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
    Set(TagDeleted),
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
    Set(Created),
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
    Set(Deprecated),
    projectRef2,
    org,
    iri"http://bbp.epfl.ch/file2",
    Set(iri"File"),
    JsonObject.apply(
      "storage" -> Json.fromString("storageId")
    )
  )

  private val elems = List(
    // Create file in proj1
    elem("1", 1L, metric1),
    // Update file in proj1
    elem("2", 2L, metric2),
    // Tag file in proj1
    elem("3", 3L, metric3),
    // Delete file tag in proj 1
    elem("4", 4L, metric4),
    // Create file in proj 2
    elem("5", 5L, metric5),
    // Deprecate file in proj 2
    elem("6", 6L, metric6)
  )

  def elem(idSuffix: String, offset: Long, metric: ProjectScopedMetric) =
    Elem.SuccessElem(EntityType("entity"), nxv + idSuffix, None, Instant.EPOCH, Offset.At(offset), metric, 1)

  val metricsStream: Stream[IO, Elem.SuccessElem[ProjectScopedMetric]] =
    Stream.emits(elems)

}
