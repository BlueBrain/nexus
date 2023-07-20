package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.http.scaladsl.model.sse.ServerSentEvent
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.FailedElemLogRow
import fs2.Stream
import io.circe.Printer
import monix.bio.{Task, UIO}

trait ProjectionErrors {

  /**
    * Saves a list of failed elems
    *
    * @param metadata
    *   the metadata of the projection
    * @param failures
    *   the FailedElem to save
    */
  def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit]

  /**
    * Get available failed elem entries for a given projection (provided by project and id), starting from a failed elem
    * offset.
    *
    * @param projectionProject
    *   the project the projection belongs to
    * @param projectionId
    *   IRI of the projection
    * @param offset
    *   failed elem offset
    */
  def failedElemEntries(
      projectionProject: ProjectRef,
      projectionId: Iri,
      offset: Offset
  ): Stream[Task, FailedElemLogRow]

  /**
    * Get available failed elem entries for a given projection by projection name, starting from a failed elem offset.
    *
    * @param projectionName
    *   the name of the projection
    * @param offset
    *   failed elem offset
    * @return
    */
  def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow]

  /**
    * Get available failed elem entries for a given projection (provided by project and id), starting from a failed elem
    * offset as a stream of Server Sent Events
    *
    * @param projectionProject
    *   the project the projection belongs to
    * @param projectionId
    *   IRI of the projection
    * @param offset
    *   failed elem offset
    */
  def failedElemSses(projectionProject: ProjectRef, projectionId: Iri, offset: Offset)(implicit
      rcr: RemoteContextResolution
  ): Stream[Task, ServerSentEvent]

}

object ProjectionErrors {

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient
  private val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

  def apply(xas: Transactors, config: QueryConfig)(implicit clock: Clock[UIO]) = new ProjectionErrors {

    val store = FailedElemLogStore(xas, config)

    override def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit] =
      store.saveFailedElems(metadata, failures)

    override def failedElemEntries(
        projectionProject: ProjectRef,
        projectionId: Iri,
        offset: Offset
    ): Stream[Task, FailedElemLogRow] = store.failedElemEntries(projectionProject, projectionId, offset)

    override def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow] =
      store.failedElemEntries(projectionName, offset)

    override def failedElemSses(projectionProject: ProjectRef, projectionId: Iri, offset: Offset)(implicit
        rcr: RemoteContextResolution
    ): Stream[Task, ServerSentEvent] =
      failedElemEntries(projectionProject, projectionId, offset).evalMap { felem =>
        felem.failedElemData.toCompactedJsonLd.map { compactJson =>
          ServerSentEvent(
            defaultPrinter.print(compactJson.json),
            "IndexingFailure",
            felem.ordering.value.toString
          )
        }
      }
  }

}
