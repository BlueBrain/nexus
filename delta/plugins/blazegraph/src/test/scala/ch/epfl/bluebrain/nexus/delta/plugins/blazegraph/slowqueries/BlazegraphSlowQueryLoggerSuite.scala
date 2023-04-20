package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.BlazegraphSlowQueryLoggerSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

object BlazegraphSlowQueryLoggerSuite {
  private val LongQueryThreshold                        = 100.milliseconds
  private val StoreWhichFails: BlazegraphSlowQueryStore = new BlazegraphSlowQueryStore {
    override def save(query: BlazegraphSlowQuery): Task[Unit] =
      Task.raiseError(new RuntimeException("error saving slow log"))

    override def removeQueriesOlderThan(instant: Instant): Task[Unit] = Task.unit

    override def listForTestingOnly(view: ViewRef): Task[List[BlazegraphSlowQuery]] = Task.pure(Nil)
  }

  private val view        = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("hippocampus"))
  private val sparqlQuery = SparqlQuery("")
  private val user        = Identity.User("Ted Lasso", Label.unsafe("epfl"))
}

class BlazegraphSlowQueryLoggerSuite extends BioSuite with Doobie.Fixture with BlazegraphSlowQueryStoreFixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, blazegraphSlowQueryStore)

  private def fixture = {
    val store  = blazegraphSlowQueryStore()
    val logger = BlazegraphSlowQueryLogger(
      store,
      LongQueryThreshold
    )
    (logger, store.listForTestingOnly(view))
  }

  test("slow query logged") {

    val (logSlowQuery, getLoggedQueries) = fixture

    for {
      _     <- logSlowQuery(
                 BlazegraphQueryContext(
                   view,
                   sparqlQuery,
                   user
                 ),
                 Task.sleep(101.milliseconds)
               )
      saved <- getLoggedQueries
    } yield {
      assertEquals(saved.size, 1)
      val onlyRecord = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assertEquals(onlyRecord.failed, false)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("slow failure logged") {

    val (logSlowQuery, getLoggedQueries) = fixture

    for {
      _     <- logSlowQuery(
                 BlazegraphQueryContext(
                   view,
                   sparqlQuery,
                   user
                 ),
                 Task.sleep(101.milliseconds) >> Task.raiseError(new RuntimeException())
               ).failed
      saved <- getLoggedQueries
    } yield {
      assertEquals(saved.size, 1)
      val onlyRecord = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assertEquals(onlyRecord.failed, true)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("fast query not logged") {

    val (logSlowQuery, getLoggedQueries) = fixture

    for {
      _     <- logSlowQuery(
                 BlazegraphQueryContext(
                   view,
                   sparqlQuery,
                   user
                 ),
                 Task.sleep(50.milliseconds)
               )
      saved <- getLoggedQueries
    } yield {
      assert(saved.isEmpty, s"expected no queries logged, actually logged $saved")
    }
  }

  test("continue when saving slow query log fails") {
    val logSlowQueries = BlazegraphSlowQueryLogger(
      StoreWhichFails,
      LongQueryThreshold
    )

    for {
      result <- logSlowQueries(
                  BlazegraphQueryContext(
                    view,
                    sparqlQuery,
                    user
                  ),
                  Task.sleep(101.milliseconds) >> Task.pure("result")
                )
    } yield {
      assertEquals(result, "result")
    }
  }
}
