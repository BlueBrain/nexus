package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.search.{Pagination, TimeRange}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PurgeElemFailures
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.testkit.clock.MutableClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.{AnyFixture, Location}

import java.time.Instant

class FailedElemLogStoreSuite extends NexusSuite with MutableClock.Fixture with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, mutableClockFixture)

  private lazy val xas = doobie()

  private val start                                    = Instant.EPOCH
  implicit private lazy val mutableClock: MutableClock = mutableClockFixture()

  private lazy val store = FailedElemLogStore(xas, QueryConfig(10, RefreshStrategy.Stop), mutableClock)

  private def createMetadata(project: ProjectRef, id: Iri) =
    ProjectionMetadata("test", s"project|$id", Some(project), Some(id))

  private val project1     = ProjectRef.unsafe("org", "proj")
  private val projection11 = nxv + "projection11"
  private val metadata11   = createMetadata(project1, projection11)
  private val projection12 = nxv + "projection12"
  private val metadata12   = createMetadata(project1, projection12)

  private val project2   = ProjectRef.unsafe("org", "proj2")
  private val metadata21 = createMetadata(project2, projection12)

  private val id    = nxv + "id"
  private val error = new RuntimeException("boom")
  private val rev   = 1

  private val entityType                                          = EntityType("Test")
  private def createFailedElem(project: ProjectRef, offset: Long) =
    FailedElem(entityType, id, Some(project), start.plusSeconds(offset), Offset.at(offset), error, rev)

  private val fail1 = createFailedElem(project1, 1L)
  private val fail2 = createFailedElem(project1, 2L)
  private val fail3 = createFailedElem(project1, 3L)
  private val fail4 = createFailedElem(project1, 4L)
  private val fail5 = createFailedElem(project2, 5L)

  private def saveFailedElem(metadata: ProjectionMetadata, failed: FailedElem) =
    mutableClock.set(failed.instant) >>
      store.save(metadata, List(failed))

  private def assertStream(metadata: ProjectionMetadata, offset: Offset, expected: List[FailedElem])(implicit
      loc: Location
  ) = {
    val expectedOffsets = expected.map(_.offset)
    for {
      _ <- store.stream(metadata.name, offset).map(_.failedElemData.offset).assert(expectedOffsets)
      _ <- (metadata.project, metadata.resourceId).traverseN { case (project, resourceId) =>
             store.stream(project, resourceId, offset).map(_.failedElemData.offset).assert(expectedOffsets)
           }
    } yield ()
  }

  private def assertList(
      project: ProjectRef,
      projectionId: Iri,
      pagination: FromPagination,
      timeRange: TimeRange,
      expected: List[FailedElem]
  )(implicit loc: Location) = {
    val expectedOffsets = expected.map(_.offset)
    store
      .list(project, projectionId, pagination, timeRange)
      .map(_.map(_.failedElemData.offset))
      .assertEquals(expectedOffsets)
  }

  test("Insert empty list of failures") {
    for {
      _ <- store.save(metadata11, List.empty)
      _ <- assertStream(metadata11, Offset.Start, List.empty)
    } yield ()
  }

  test("Insert several failures") {
    for {
      _ <- saveFailedElem(metadata11, fail1)
      _ <- saveFailedElem(metadata12, fail2)
      _ <- saveFailedElem(metadata12, fail3)
      _ <- saveFailedElem(metadata12, fail4)
      _ <- saveFailedElem(metadata21, fail5)
    } yield ()
  }

  test(s"Get stream of failures for ${metadata11.name}") {
    for {
      entries <- store.stream(metadata11.name, Offset.start).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.projectionMetadata, metadata11)
      _        = assertEquals(r.ordering, Offset.At(1L))
      _        = assertEquals(r.instant, fail1.instant)
      elem     = r.failedElemData
      _        = assertEquals(elem.offset, Offset.At(1L))
      _        = assertEquals(elem.errorType, "java.lang.RuntimeException")
      _        = assertEquals(elem.id, id)
      _        = assertEquals(elem.entityType, entityType)
      _        = assertEquals(elem.rev, rev)
      _        = assertEquals(elem.project, Some(project1))
    } yield ()
  }

  test(s"Get a stream of all failures") {
    assertStream(metadata12, Offset.start, List(fail2, fail3, fail4))
  }

  test("Get an empty stream for an unknown projection") {
    val unknownMetadata = createMetadata(ProjectRef.unsafe("xxx", "xxx"), nxv + "xxx")
    assertStream(unknownMetadata, Offset.start, List.empty)
  }

  test(s"List all failures") {
    assertList(project1, projection12, Pagination.OnePage, TimeRange.Anytime, List(fail2, fail3, fail4))
  }

  test(s"Count all failures") {
    store.count(project1, projection12, TimeRange.Anytime).assertEquals(3L)
  }

  test(s"Paginate failures to get one result") {
    assertList(project1, projection12, FromPagination(1, 1), TimeRange.Anytime, List(fail3))
  }

  test(s"Paginate failures to get the last results ") {
    assertList(project1, projection12, FromPagination(1, 2), TimeRange.Anytime, List(fail3, fail4))
  }

  private val after = TimeRange.After(fail3.instant)
  test(s"List failures after a given time") {
    assertList(project1, projection12, Pagination.OnePage, after, List(fail3, fail4))
  }

  test(s"Count failures after a given time") {
    store.count(project1, projection12, after).assertEquals(2L)
  }

  private val before = TimeRange.Before(fail3.instant)
  test(s"List failures before a given time") {
    assertList(project1, projection12, Pagination.OnePage, before, List(fail2, fail3))
  }

  test(s"Count failures before a given time") {
    store.count(project1, projection12, before).assertEquals(2L)
  }

  private val between = TimeRange.Between(fail2.instant.plusMillis(1L), fail3.instant.plusMillis(1L))
  test(s"List failures within the time window") {
    assertList(project1, projection12, Pagination.OnePage, between, List(fail3))
  }

  test(s"Count failures within the time window") {
    store.count(project1, projection12, between).assertEquals(1L)
  }

  test("Purge failures before given instant") {
    val purgeElemFailures = new PurgeElemFailures(xas)

    for {
      _ <- store.count.assertEquals(5L)
      _ <- purgeElemFailures(start.minusMillis(500L))
      // no elements are deleted before the start instant
      _ <- store.count.assertEquals(5L)
      _ <- purgeElemFailures(start.plusSeconds(10L))
      // all elements were deleted after 14 days
      _ <- store.count.assertEquals(0L)
    } yield ()
  }

}
