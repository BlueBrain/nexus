package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.PurgeElemFailures
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class FailedElemLogStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = FailedElemLogStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val name     = "offset"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = iri"https://resource"
  private val metadata = ProjectionMetadata("test", name, Some(project), Some(resource))

  private val id    = nxv + "id"
  private val error = new RuntimeException("boom")
  private val rev   = 1
  private val fail1 = FailedElem(EntityType("ACL"), id, Some(project), Instant.EPOCH, Offset.At(42L), error, rev)
  private val fail2 = FailedElem(EntityType("Schema"), id, Some(project), Instant.EPOCH, Offset.At(42L), error, rev)

  test("Return no failed elem entries by name") {
    for {
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return no failed elem entries by (project, id)") {
    for {
      entries <- store.failedElemEntries(project, resource, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Insert empty list of failed elem") {
    for {
      _       <- store.saveFailedElems(metadata, List.empty)
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return no failed elem entries by name") {
    for {
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return no failed elem entries by (project, id)") {
    for {
      entries <- store.failedElemEntries(project, resource, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Insert empty list of failed elem") {
    for {
      _       <- store.saveFailedElems(metadata, List.empty)
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Insert failed elem") {
    for {
      _       <- store.saveFailedElems(metadata, List(fail1))
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.projectionMetadata, metadata)
      _        = assertEquals(r.ordering, Offset.At(1L))
      _        = assertEquals(r.instant, Instant.EPOCH)
      elem     = r.failedElemData
      _        = assertEquals(elem.offset, Offset.At(42L))
      _        = assertEquals(elem.errorType, "java.lang.RuntimeException")
      _        = assertEquals(elem.id, id)
      _        = assertEquals(elem.entityType, EntityType("ACL"))
      _        = assertEquals(elem.rev, rev)
      _        = assertEquals(elem.project, Some(project))
    } yield ()
  }

  test("Insert several failed elem") {
    for {
      _       <- store.saveFailedElems(metadata, List(fail1, fail2))
      entries <- store.failedElemEntries(name, Offset.start).compile.toList
      _        = entries.assertSize(3)
    } yield ()
  }

  test("Return failed elem entries by (project, id)") {
    for {
      entries <- store.failedElemEntries(project, resource, Offset.start).compile.toList
      _        = entries.assertSize(3)
    } yield ()
  }

  test("Return empty if no failed elem is found by name") {
    for {
      entries <- store.failedElemEntries("other", Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return empty if not found by (project, id)") {
    for {
      entries <- store.failedElemEntries(project, iri"https://example.com", Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Purge failed elements after predefined ttl") {
    val failedElemTtl = 14.days

    lazy val purgeElemFailures: FiniteDuration => PurgeElemFailures = timeTravel =>
      new PurgeElemFailures(xas, failedElemTtl)(
        IOFixedClock.ioClock(Instant.EPOCH.plusMillis(timeTravel.toMillis))
      )

    for {
      _        <- purgeElemFailures(failedElemTtl - 500.millis)()
      entries  <- store.failedElemEntries(project, resource, Offset.start).compile.toList
      _         = entries.assertSize(3) // no elements are deleted after 13 days
      _        <- purgeElemFailures(failedElemTtl + 500.millis)()
      entries2 <- store.failedElemEntries(project, resource, Offset.start).compile.toList
      _         = entries2.assertEmpty() // all elements were deleted after 14 days
    } yield ()
  }

}
