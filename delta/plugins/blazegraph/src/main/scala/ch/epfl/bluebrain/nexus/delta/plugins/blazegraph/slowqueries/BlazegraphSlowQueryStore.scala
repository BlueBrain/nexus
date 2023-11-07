package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

import java.time.Instant

/**
  * Persistence operations for slow query logs
  */
trait BlazegraphSlowQueryStore {
  def save(query: BlazegraphSlowQuery): IO[Unit]
  def removeQueriesOlderThan(instant: Instant): IO[Unit]
  def listForTestingOnly(view: ViewRef): IO[List[BlazegraphSlowQuery]]
}

object BlazegraphSlowQueryStore {
  def apply(xas: Transactors): BlazegraphSlowQueryStore = {
    new BlazegraphSlowQueryStore {
      override def save(query: BlazegraphSlowQuery): IO[Unit] = {
        sql""" INSERT INTO blazegraph_queries(project, view_id, instant, duration, subject, query, failed)
             | VALUES(${query.view.project}, ${query.view.viewId}, ${query.instant}, ${query.duration}, ${query.subject.asJson}, ${query.query.value}, ${query.failed})
        """.stripMargin.update.run
          .transact(xas.writeCE)
          .void
      }

      override def listForTestingOnly(view: ViewRef): IO[List[BlazegraphSlowQuery]] = {
        sql""" SELECT project, view_id, instant, duration, subject, query, failed FROM public.blazegraph_queries
             |WHERE view_id = ${view.viewId} AND project = ${view.project}
           """.stripMargin
          .query[BlazegraphSlowQuery]
          .stream
          .transact(xas.readCE)
          .compile
          .toList
      }

      override def removeQueriesOlderThan(instant: Instant): IO[Unit] = {
        sql""" DELETE FROM public.blazegraph_queries
             |WHERE instant < $instant
           """.stripMargin.update.run
          .transact(xas.writeCE)
          .void
      }
    }
  }
}
