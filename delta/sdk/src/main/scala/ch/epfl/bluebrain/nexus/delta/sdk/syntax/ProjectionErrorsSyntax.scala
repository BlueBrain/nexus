package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.sse.ServerSentEvent
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.ProjectionErrorsSyntax.ProjectionErrorsOps
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import io.circe.Printer
import monix.bio.{Task, UIO}

/**
  * Allows to extend the methods from [[ProjectionErrors]] by adding higher-level methods
  */
trait ProjectionErrorsSyntax {

  implicit def projectionErrorsOps(projectionErrors: ProjectionErrors): ProjectionErrorsOps = new ProjectionErrorsOps(
    projectionErrors
  )
}

object ProjectionErrorsSyntax {

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient
  private val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

  final class ProjectionErrorsOps(val projectionErrors: ProjectionErrors) extends AnyVal {

    /**
      * Get available failed elem entries for a given projection (provided by project and id), starting from a failed
      * elem offset as a stream of Server Sent Events
      *
      * @param projectionProject
      *   the project the projection belongs to
      * @param projectionId
      *   IRI of the projection
      * @param offset
      *   failed elem offset
      */
    def sses(projectionProject: ProjectRef, projectionId: Iri, offset: Offset)(implicit
        rcr: RemoteContextResolution
    ): fs2.Stream[Task, ServerSentEvent] =
      projectionErrors.failedElemEntries(projectionProject, projectionId, offset).evalMap { felem =>
        felem.failedElemData.toCompactedJsonLd.map { compactJson =>
          ServerSentEvent(
            defaultPrinter.print(compactJson.json),
            "IndexingFailure",
            felem.ordering.value.toString
          )
        }
      }

    /**
      * Return a search results for the given view on a time window ordered by instant
      *
      * @param view
      *   its identifier
      * @param pagination
      *   the pagination to apply
      * @param timeRange
      *   the time range to restrict on
      * @return
      */
    def search(view: ViewRef, pagination: FromPagination, timeRange: TimeRange): UIO[SearchResults[FailedElemData]] = {
      for {
        results <- projectionErrors.list(view.project, view.viewId, pagination, timeRange)
        count   <- projectionErrors.count(view.project, view.viewId, timeRange)
      } yield SearchResults(
        count,
        results.map {
          _.failedElemData
        }
      )
    }.widen[SearchResults[FailedElemData]]

  }

}
