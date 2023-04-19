package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.Read
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

case class BlazegraphSlowQuery(
    view: ViewRef,
    query: SparqlQuery,
    duration: FiniteDuration,
    occurredAt: Instant,
    subject: Subject
)

object BlazegraphSlowQuery {

  implicit val read: Read[BlazegraphSlowQuery] = {
    Read[(ProjectRef, Iri, Instant, Long, Json, String)].map {
      case (project, viewId, occurredAt, duration, subject, query) =>
        BlazegraphSlowQuery(
          ViewRef(project, viewId),
          SparqlQuery(query),
          duration.milliseconds,
          occurredAt,
          subject.as[Subject] match {
            case Right(value) => value
            case Left(e)      => throw e
          }
        )
    }
  }
}
