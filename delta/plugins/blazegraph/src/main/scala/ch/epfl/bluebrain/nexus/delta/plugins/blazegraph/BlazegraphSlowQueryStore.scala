package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.util.fragment.Fragment
import monix.bio.Task
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
trait BlazegraphSlowQueryStore {
  def save(query: BlazegraphSlowQuery): Task[Unit]
}

case class BlazegraphQueryContext(viewId: Iri, project: ProjectRef, query: SparqlQuery, subject: Subject)
case class BlazegraphSlowQuery(
    viewId: Iri,
    project: ProjectRef,
    query: SparqlQuery,
    duration: FiniteDuration,
    occurredAt: Instant,
    subject: Subject
)

object BlazegraphSlowQueryStore {

  private val scriptPath = "scripts/postgres/blazegraph_slow_queries_table.ddl"

  private val logger: Logger = Logger[BlazegraphSlowQueryStore.type]

  /**
    * Create a token store
    */
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
                 | VALUES(${query.project}, ${query.viewId}, ${query.occurredAt}, ${query.duration}, ${query.subject.toString}, ${query.query.value})
            """.stripMargin.update.run.transact(xas.write).void
          }
        }
      )
  }
}
