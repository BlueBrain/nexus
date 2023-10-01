package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.MigrateCompositeViews.{eventsToMigrate, statesToMigrate}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.MigrateCompositeViewsSuite.{loadEvent, loadState}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent.{CompositeViewCreated, CompositeViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewEvent, CompositeViewState, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

import doobie.Get
import doobie.implicits._
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}
import munit.{AnyFixture, Location}

import java.time.Instant

class MigrateCompositeViewsSuite extends BioSuite with Doobie.Fixture with ClasspathResourceUtils {

  private val proj = ProjectRef.unsafe("myorg", "myproj")

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  implicit private lazy val xas: Transactors = doobie()

  private val eventSerializer                    = CompositeViewEvent.serializer
  implicit val eventGet: Get[CompositeViewEvent] = eventSerializer.getValue
  private val stateSerializer                    = CompositeViewState.serializer
  implicit val stateGet: Get[CompositeViewState] = stateSerializer.getValue

  private def assertMigratedValue(value: CompositeViewValue, rev: Int)(implicit loc: Location): Unit = {
    assertEquals(value.sourceIndexingRev.value, rev)
    value.projections.toNel.toList.foreach { case (_, projection) =>
      assertEquals(projection.indexingRev.value, rev)
    }
  }

  test("Insert states and events and run migration") {
    for {
      _ <- initPartitions(xas, proj)
      // Events to migrate
      _ <- loadEvent("migration/event-created.json")
      _ <- loadEvent("migration/event-updated.json")
      // Events already migrated
      _ <- loadEvent("composite-views/database/named-view-created.json")
      _ <- loadEvent("composite-views/database/named-view-updated.json")
      // States to migrate
      _ <- loadState(Tag.latest, "migration/state.json")
      _ <- loadState(UserTag.unsafe("v1.0"), "migration/state.json")
      _ <- loadState(Tag.latest, "composite-views/database/view-state.json")
      _ <- loadState(UserTag.unsafe("v1.0"), "composite-views/database/view-state.json")
      _ <- eventsToMigrate(xas).map(_.assertSize(2))
      _ <- statesToMigrate(xas).map(_.assertSize(2))
      _ <- new MigrateCompositeViews(xas).run.assert((2, 2))
      // Making sure all has been updated
      _ <- eventsToMigrate(xas).map(_.assertSize(0))
      _ <- statesToMigrate(xas).map(_.assertSize(0))
    } yield ()
  }

  test("Check events have been correctly updated") {
    def assertEvents(events: List[CompositeViewEvent]): Unit = {
      // Only the migrated events have an indexing revision equals to
      events.filter(_.id == iri"http://example.com/to-migrate").foreach {
        case c: CompositeViewCreated => assertMigratedValue(c.value, c.rev)
        case u: CompositeViewUpdated => assertMigratedValue(u.value, u.rev)
        case _                       => ()
      }
    }

    val selectEvents = sql"SELECT value FROM scoped_events"
      .query[CompositeViewEvent]
      .to[List]
      .transact(xas.read)

    selectEvents.map(assertEvents)
  }

  test("Check states have been correctly updated") {
    val selectStates = sql"SELECT value FROM scoped_states"
      .query[CompositeViewState]
      .to[List]
      .transact(xas.read)

    selectStates.map { list =>
      list.filter(_.id != iri"http://example.com/to-migrate").foreach { state =>
        assertMigratedValue(state.value, state.rev)
      }
    }
  }
}

object MigrateCompositeViewsSuite extends ClasspathResourceUtils {

  def extractIdentifiers(json: JsonObject) = IO.fromOption {
    for {
      rawProject <- json("project").flatMap(_.asString)
      project    <- ProjectRef.parse(rawProject).toOption
      id         <- json("id").flatMap(_.asString)
      rev        <- json("rev").flatMap(_.asNumber.flatMap(_.toInt))
    } yield (project, id, rev)
  }

  def loadEvent(jsonPath: String)(implicit xas: Transactors, classLoader: ClassLoader): IO[Unit, Unit] = {
    def insert(project: ProjectRef, id: String, rev: Int, json: JsonObject) =
      sql"""
           | INSERT INTO scoped_events (
           |  type,
           |  org,
           |  project,
           |  id,
           |  rev,
           |  value,
           |  instant
           | )
           | VALUES (
           |  ${CompositeViews.entityType},
           |  ${project.organization},
           |  ${project.project},
           |  $id,
           |  $rev,
           |  ${json.asJson},
           |  ${Instant.EPOCH}
           | )""".stripMargin.update.run.void.transact(xas.write).hideErrors

    for {
      json               <- ioJsonObjectContentOf(jsonPath): UIO[JsonObject]
      (project, id, rev) <- extractIdentifiers(json)
      _                  <- insert(project, id, rev, json)
    } yield ()
  }

  def loadState(tag: Tag, jsonPath: String)(implicit xas: Transactors, classLoader: ClassLoader) = {
    def insert(project: ProjectRef, id: String, rev: Int, json: JsonObject): UIO[Unit] =
      sql"""
           | INSERT INTO scoped_states (
           |  type,
           |  org,
           |  project,
           |  id,
           |  tag,
           |  rev,
           |  value,
           |  deprecated,
           |  instant
           | )
           | VALUES (
           |  ${CompositeViews.entityType},
           |  ${project.organization},
           |  ${project.project},
           |  $id,
           |  $tag,
           |  $rev,
           |  ${json.asJson},
           |  ${false},
           |  ${Instant.EPOCH}
           | )""".stripMargin.update.run.void.transact(xas.write).hideErrors

    for {
      json               <- ioJsonObjectContentOf(jsonPath): UIO[JsonObject]
      (project, id, rev) <- extractIdentifiers(json)
      _                  <- insert(project, id, rev, json)
    } yield ()
  }
}
