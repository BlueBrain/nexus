package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.util.fragment.Fragment
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait BlazegraphSlowQueryStore {
  def save(query: BlazegraphSlowQuery): Task[Unit]
}

case class BlazegraphSlowQuery(
    view: ViewRef,
    query: SparqlQuery,
    duration: FiniteDuration,
    occurredAt: Instant,
    subject: Subject
)

object BlazegraphSlowQueryStore {

  private val scriptPath = "scripts/postgres/blazegraph_slow_queries_table.ddl"

  private val logger: Logger = Logger[BlazegraphSlowQueryStore.type]

  def apply(xas: Transactors, tablesAutocreate: Boolean): Task[BlazegraphSlowQueryStore] = {
    implicit val classLoader: ClassLoader = getClass.getClassLoader
    Task
      .when(tablesAutocreate) {
        for {
          ddl <- resourceFrom(scriptPath)
          _   <- Fragment.const(ddl).update.run.transact(xas.write)
          _   <- Task.delay(logger.info(s"Created Blazegraph plugin tables"))
        } yield ()
      }
      .as(
        new BlazegraphSlowQueryStore {
          override def save(query: BlazegraphSlowQuery): Task[Unit] = {
            sql""" INSERT INTO blazegraph_slow_queries(project, view_id, instant, duration, subject, query)
                 | VALUES(${query.view.project}, ${query.view.viewId}, ${query.occurredAt}, ${query.duration}, ${query.subject.toString}, ${query.query.value})
            """.stripMargin.update.run.transact(xas.write).void
          }
        }
      )
  }
}
