package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.Read
import io.circe.Json
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * A blazegraph query which took too long
 * @param view
 *   the view
 * @param query
 *   the query
 * @param failed
 *   whether the query failed
 * @param duration
 *   how long the query took
 * @param instant
 *   when the query finished
 * @param subject
 *   who ran the query
 */
final case class BlazegraphSlowQuery(
                                      view: ViewRef,
                                      query: SparqlQuery,
                                      failed: Boolean,
                                      duration: FiniteDuration,
                                      instant: Instant,
                                      subject: Subject
)

object BlazegraphSlowQuery {

  implicit val read: Read[BlazegraphSlowQuery] = {
    Read[(ProjectRef, Iri, Instant, Long, Json, String, Boolean)].map {
      case (project, viewId, occurredAt, duration, subject, query, failed) =>
        BlazegraphSlowQuery(
          ViewRef(project, viewId),
          SparqlQuery(query),
          failed,
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
