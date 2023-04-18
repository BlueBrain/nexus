package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps
import monix.bio.Task
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.ProjectionProgressRow
import doobie.Read
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}

trait BlazegraphSlowQueryStore {
  def save(query: BlazegraphSlowQuery): Task[Unit]
  def listForTestingOnly(view: ViewRef): Task[List[BlazegraphSlowQuery]]
}

object BlazegraphSlowQueryStore {
  def apply(xas: Transactors): BlazegraphSlowQueryStore = {
    new BlazegraphSlowQueryStoreImpl(xas)
  }
}

object BlazegraphSlowQuery {
  implicit val projectionProgressRowRead: Read[ProjectionProgressRow] = {
    Read[(String, String, Option[ProjectRef], Option[Iri], Long, Long, Long, Long, Instant, Instant)].map {
      case (name, module, project, resourceId, offset, processed, discarded, failed, createdAt, updatedAt) =>
        ProjectionProgressRow(
          name,
          module,
          project,
          resourceId,
          ProjectionProgress(Offset.from(offset), updatedAt, processed, discarded, failed),
          createdAt,
          updatedAt
        )
    }
  }

  implicit val read: Read[BlazegraphSlowQuery] = {
    Read[(ProjectRef, Iri, Instant, Long, Json, String)].map {
      case (project, viewId, occurredAt, duration, subject, query) =>
        BlazegraphSlowQuery(
          ViewRef(project, viewId),
          SparqlQuery(query),
          duration.milliseconds,
          occurredAt,
          subject.as[Subject].toTry.get
        )
    }
  }
}

case class BlazegraphSlowQuery(
    view: ViewRef,
    query: SparqlQuery,
    duration: FiniteDuration,
    occurredAt: Instant,
    subject: Subject
)

class BlazegraphSlowQueryStoreImpl(xas: Transactors) extends BlazegraphSlowQueryStore {
  override def save(query: BlazegraphSlowQuery): Task[Unit] = {
    sql""" INSERT INTO blazegraph_queries(project, view_id, instant, duration, subject, query)
         | VALUES(${query.view.project}, ${query.view.viewId}, ${query.occurredAt}, ${query.duration}, ${query.subject.asJson}, ${query.query.value})
    """.stripMargin.update.run
      .transact(xas.write)
      .void
  }

  override def listForTestingOnly(view: ViewRef): Task[List[BlazegraphSlowQuery]] = {
    sql""" SELECT project, view_id, instant, duration, subject, query FROM public.blazegraph_queries
         |WHERE view_id = ${view.viewId} AND project = ${view.project}
       """.stripMargin
      .query[BlazegraphSlowQuery]
      .stream
      .transact(xas.read)
      .compile
      .toList
  }
}
