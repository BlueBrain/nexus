package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps
import monix.bio.Task
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait BlazegraphSlowQueryStore {
  def save(query: BlazegraphSlowQuery): Task[Unit]
}

object BlazegraphSlowQueryStore {
  def apply(xas: Transactors): BlazegraphSlowQueryStore = {
    new BlazegraphSlowQueryStoreImpl(xas)
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
    """.stripMargin.update.run.transact(xas.write).void
  }
}
