package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps
import monix.bio.Task
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

import java.time.Instant

trait BlazegraphSlowQuerySink {
  def save(query: BlazegraphSlowQuery): Task[Unit]
}

trait BlazegraphSlowQueryStore extends BlazegraphSlowQuerySink {
  def removeQueriesOlderThan(instant: Instant): Task[Unit]
  def listForTestingOnly(view: ViewRef): Task[List[BlazegraphSlowQuery]]
}

object BlazegraphSlowQueryStore {
  def apply(xas: Transactors): BlazegraphSlowQueryStore = {
    new BlazegraphSlowQueryStore {
      override def save(query: BlazegraphSlowQuery): Task[Unit] = {
        sql""" INSERT INTO blazegraph_queries(project, view_id, instant, duration, subject, query, was_error)
             | VALUES(${query.view.project}, ${query.view.viewId}, ${query.occurredAt}, ${query.duration}, ${query.subject.asJson}, ${query.query.value}, ${query.wasError})
        """.stripMargin.update.run
          .transact(xas.write)
          .void
      }

      override def listForTestingOnly(view: ViewRef): Task[List[BlazegraphSlowQuery]] = {
        sql""" SELECT project, view_id, instant, duration, subject, query, was_error FROM public.blazegraph_queries
             |WHERE view_id = ${view.viewId} AND project = ${view.project}
           """.stripMargin
          .query[BlazegraphSlowQuery]
          .stream
          .transact(xas.read)
          .compile
          .toList
      }

      override def removeQueriesOlderThan(instant: Instant): Task[Unit] = {
        sql""" DELETE FROM public.blazegraph_queries
             |WHERE instant < $instant
           """.stripMargin.update.run
          .transact(xas.write)
          .void
      }
    }
  }
}
