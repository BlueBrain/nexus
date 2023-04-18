package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.Task

import java.util
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class BlazegraphSlowQueryLoggerSuite extends BioSuite {
  private val LongQueryThreshold = 100.milliseconds

  private val view        = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("hippocampus"))
  private val sparqlQuery = SparqlQuery("")
  private val user        = Identity.User("Ted Lasso", Label.unsafe("epfl"))

  private def fixture: (BlazegraphSlowQueryLogger, () => List[BlazegraphSlowQuery]) = {
    val saved   = new util.ArrayList[BlazegraphSlowQuery]()
    val service = BlazegraphSlowQueryLogger.store(
      new BlazegraphSlowQueryStore {
        override def save(query: BlazegraphSlowQuery): Task[Unit] = Task.delay {
          saved.add(query)
          ()
        }
      },
      LongQueryThreshold
    )

    (service, () => saved.asScala.toList)
  }

  test("test slow query logged") {

    val (service, getSaved) = fixture

    for {
      _ <- service.logSlowQueries(
             BlazegraphQueryContext(
               view,
               sparqlQuery,
               user
             ),
             Task.sleep(101.milliseconds)
           )
    } yield {
      val saved      = getSaved()
      assertEquals(saved.size, 1)
      val onlyRecord = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("test slow failure logged") {

    val (service, getSaved) = fixture

    for {
      _ <- service
             .logSlowQueries(
               BlazegraphQueryContext(
                 view,
                 sparqlQuery,
                 user
               ),
               Task.sleep(101.milliseconds) >> Task.raiseError(new RuntimeException())
             )
             .failed
    } yield {
      val saved      = getSaved()
      assertEquals(saved.size, 1)
      val onlyRecord = saved.head
      assertEquals(onlyRecord.view, view)
      assertEquals(onlyRecord.query, sparqlQuery)
      assertEquals(onlyRecord.subject, user)
      assert(onlyRecord.duration > 100.milliseconds)
    }
  }

  test("test fast query not logged") {

    val (service, getSaved) = fixture

    for {
      _ <- service.logSlowQueries(
             BlazegraphQueryContext(
               view,
               sparqlQuery,
               user
             ),
             Task.sleep(50.milliseconds)
           )
    } yield {
      val saved = getSaved()
      assert(saved.isEmpty, s"expected no queries logged, actually logged $saved")
    }
  }
}
