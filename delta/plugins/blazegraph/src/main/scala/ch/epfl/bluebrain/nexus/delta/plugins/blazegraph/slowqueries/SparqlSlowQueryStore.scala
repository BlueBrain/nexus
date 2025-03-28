package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.SparqlSlowQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import doobie.syntax.all._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

import java.time.Instant

/**
  * Persistence operations for slow query logs
  */
trait SparqlSlowQueryStore {
  def save(query: SparqlSlowQuery): IO[Unit]
  def listForTestingOnly(view: ViewRef): IO[List[SparqlSlowQuery]]
  def removeQueriesOlderThan(instant: Instant): IO[Unit]
}

object SparqlSlowQueryStore {
  def apply(xas: Transactors): SparqlSlowQueryStore = {
    new SparqlSlowQueryStore {
      override def save(query: SparqlSlowQuery): IO[Unit] = {
        sql""" INSERT INTO blazegraph_queries(project, view_id, instant, duration, subject, query, failed)
             | VALUES(${query.view.project}, ${query.view.viewId}, ${query.instant}, ${query.duration}, ${query.subject.asJson}, ${query.query.value}, ${query.failed})
        """.stripMargin.update.run
          .transact(xas.write)
          .void
      }

      override def listForTestingOnly(view: ViewRef): IO[List[SparqlSlowQuery]] = {
        sql""" SELECT project, view_id, instant, duration, subject, query, failed FROM public.blazegraph_queries
             |WHERE view_id = ${view.viewId} AND project = ${view.project}
           """.stripMargin
          .query[SparqlSlowQuery]
          .stream
          .transact(xas.read)
          .compile
          .toList
      }

      override def removeQueriesOlderThan(instant: Instant): IO[Unit] = {
        sql""" DELETE FROM public.blazegraph_queries
             |WHERE instant < $instant
           """.stripMargin.update.run
          .transact(xas.write)
          .void
      }
    }
  }
}
