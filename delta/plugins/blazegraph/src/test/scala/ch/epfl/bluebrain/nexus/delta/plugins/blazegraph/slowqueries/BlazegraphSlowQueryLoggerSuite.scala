package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import cats.implicits.catsSyntaxFlatMapOps
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.BlazegraphSlowQueryLoggerSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object BlazegraphSlowQueryLoggerSuite {
  private val LongQueryThreshold                        = 100.milliseconds
  private val StoreWhichFails: BlazegraphSlowQueryStore = new BlazegraphSlowQueryStore {
    override def save(query: BlazegraphSlowQuery): IO[Unit] =
      IO.raiseError(new RuntimeException("error saving slow log"))

    override def removeQueriesOlderThan(instant: Instant): IO[Unit] = IO.unit

    override def listForTestingOnly(view: ViewRef): IO[List[BlazegraphSlowQuery]] = IO.pure(Nil)
  }

  private val view        = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("hippocampus"))
  private val sparqlQuery = SparqlQuery("")
  private val user        = Identity.User("Ted Lasso", Label.unsafe("epfl"))
}

class BlazegraphSlowQueryLoggerSuite extends CatsEffectSuite with Doobie.Fixture with BlazegraphSlowQueryStoreFixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, blazegraphSlowQueryStore)

  private def fixture = {
    val store  = blazegraphSlowQueryStore()
    val logger = BlazegraphSlowQueryLogger(
      store,
      LongQueryThreshold
    )
    (logger, store.listForTestingOnly(view))
  }

  private def assertSavedQuery(actual: BlazegraphSlowQuery, failed: Boolean, minDuration: FiniteDuration): Unit = {
    assertEquals(actual.view, view)
    assertEquals(actual.query, sparqlQuery)
    assertEquals(actual.subject, user)
    assertEquals(actual.failed, failed)
    assertEquals(actual.instant, Instant.EPOCH)
    assert(actual.duration >= minDuration)
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
                 IO.sleep(101.milliseconds)
               )
      saved <- getLoggedQueries
    } yield {
      assertEquals(saved.size, 1)
      assertSavedQuery(saved.head, failed = false, 101.millis)
      val onlyRecord: BlazegraphSlowQuery = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assertEquals(onlyRecord.failed, false)
      assertEquals(onlyRecord.instant, Instant.EPOCH)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("slow failure logged") {

    val (logSlowQuery, getLoggedQueries) = fixture

    for {
      attempt <- logSlowQuery(
                   BlazegraphQueryContext(
                     view,
                     sparqlQuery,
                     user
                   ),
                   IO.sleep(101.milliseconds) >> IO.raiseError(new RuntimeException())
                 ).attempt
      saved   <- getLoggedQueries
    } yield {
      assert(attempt.isLeft)
      assertEquals(saved.size, 1)
      assertSavedQuery(saved.head, failed = true, 101.millis)
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
                 IO.sleep(50.milliseconds)
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

    logSlowQueries(
      BlazegraphQueryContext(
        view,
        sparqlQuery,
        user
      ),
      IO.sleep(101.milliseconds).as("result")
    ).assertEquals("result")
  }
}
