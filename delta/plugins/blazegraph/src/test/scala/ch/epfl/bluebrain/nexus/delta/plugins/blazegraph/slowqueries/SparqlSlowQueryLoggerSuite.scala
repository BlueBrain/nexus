package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.SparqlSlowQueryLoggerSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.SparqlSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object SparqlSlowQueryLoggerSuite {
  private val LongQueryThreshold                    = 100.milliseconds
  private val StoreWhichFails: SparqlSlowQueryStore = new SparqlSlowQueryStore {
    override def save(query: SparqlSlowQuery): IO[Unit] =
      IO.raiseError(new RuntimeException("error saving slow log"))

    override def removeQueriesOlderThan(instant: Instant): IO[Unit] = IO.unit

    override def listForTestingOnly(view: ViewRef): IO[List[SparqlSlowQuery]] = IO.pure(Nil)
  }

  private val view        = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("hippocampus"))
  private val sparqlQuery = SparqlQuery("")
  private val user        = Identity.User("Ted Lasso", Label.unsafe("epfl"))
}

class SparqlSlowQueryLoggerSuite extends NexusSuite with Doobie.Fixture with BlazegraphSlowQueryStoreFixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, blazegraphSlowQueryStore)

  private def fixture = {
    val store  = blazegraphSlowQueryStore()
    val logger = SparqlSlowQueryLogger(
      store,
      LongQueryThreshold,
      clock
    )
    (logger, store.listForTestingOnly(view))
  }

  private def assertSavedQuery(actual: SparqlSlowQuery, failed: Boolean, minDuration: FiniteDuration): Unit = {
    assertEquals(actual.view, view)
    assertEquals(actual.query, sparqlQuery)
    assertEquals(actual.subject, user)
    assertEquals(actual.failed, failed)
    assertEquals(actual.instant, Instant.EPOCH)
    assert(actual.duration >= minDuration)
  }

  test("Slow query is logged") {

    val (logSlowQuery, getLoggedQueries) = fixture
    val slowQuery                        = IO.sleep(101.milliseconds)

    for {
      _     <- logSlowQuery(view, sparqlQuery, user, slowQuery)
      saved <- getLoggedQueries
    } yield {
      assertEquals(saved.size, 1)
      assertSavedQuery(saved.head, failed = false, 101.millis)
      val onlyRecord: SparqlSlowQuery = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assertEquals(onlyRecord.failed, false)
      assertEquals(onlyRecord.instant, Instant.EPOCH)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("Slow failure logged") {

    val (logSlowQuery, getLoggedQueries) = fixture
    val slowFailingQuery                 = IO.sleep(101.milliseconds) >> IO.raiseError(new RuntimeException())

    for {
      attempt <- logSlowQuery(view, sparqlQuery, user, slowFailingQuery).attempt
      saved   <- getLoggedQueries
    } yield {
      assert(attempt.isLeft)
      assertEquals(saved.size, 1)
      assertSavedQuery(saved.head, failed = true, 101.millis)
    }
  }

  test("Fast query is not logged") {

    val (logSlowQuery, getLoggedQueries) = fixture
    val fastQuery                        = IO.sleep(50.milliseconds)

    for {
      _     <- logSlowQuery(view, sparqlQuery, user, fastQuery)
      saved <- getLoggedQueries
    } yield {
      assert(saved.isEmpty, s"expected no queries logged, actually logged $saved")
    }
  }

  test("continue when saving slow query log fails") {
    val logSlowQueries = SparqlSlowQueryLogger(
      StoreWhichFails,
      LongQueryThreshold,
      clock
    )
    val query          = IO.sleep(101.milliseconds).as("result")

    logSlowQueries(view, sparqlQuery, user, query).assertEquals("result")
  }
}
