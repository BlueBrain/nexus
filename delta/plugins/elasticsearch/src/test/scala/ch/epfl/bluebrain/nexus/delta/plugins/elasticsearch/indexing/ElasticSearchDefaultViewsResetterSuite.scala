package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, SuccessElemStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import fs2.Stream
import munit.AnyFixture

import java.time.Instant

class ElasticSearchDefaultViewsResetterSuite
    extends NexusSuite
    with Doobie.Fixture
    with ElasticSearchClientSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)
  private lazy val xas                           = doobie()

  private val defaultEsViewId   = "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"
  private val defaultIndexLabel = IndexLabel.unsafe("default")
  private val project           = ProjectRef.unsafe("org", "proj")
  private val project2          = ProjectRef.unsafe("org", "proj2")

  private val defaultView  = ActiveViewDef(
    ViewRef(project, iri"$defaultEsViewId"),
    projection = "projection",
    None,
    SelectFilter.latest,
    index = defaultIndexLabel,
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    IndexingRev.init,
    1
  )
  private val customViewId = "https://other.id"
  private val customView   = defaultView.copy(ref = ViewRef(project, iri"$customViewId"))

  // TODO: Find how to move this to beforeAll
  test("Setup: partitions should be created") {
    initPartitions(xas, project, project2)
  }

  test("The resetter should delete scoped events for the default view") {
    insertViewEvent(defaultEsViewId, project).transact(xas.write) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetView(defaultView) >>
      assertIO(eventsCount, 0)
  }

  test("The resetter should delete scoped states for the default view") {
    insertViewState(defaultEsViewId, project).transact(xas.write) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetView(defaultView) >>
      assertIO(statesCount, 0)
  }

  test("The resetter should delete projection offsets for the default view") {
    insertViewProjectionOffset(defaultEsViewId, project).transact(xas.write) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetView(defaultView) >>
      assertIO(projectionOffsetCount, 0)
  }

  test("The resetter should not delete a scoped event for a custom view") {
    insertViewEvent(customViewId, project).transact(xas.write) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetView(customView) >>
      assertIO(eventsCount, 1)
  }

  test("The resetter should not delete a scoped state for a custom view") {
    insertViewState(customViewId, project).transact(xas.write) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetView(customView) >>
      assertIO(statesCount, 1)
  }

  test("The resetter should not delete projection offsets for a custom view") {
    insertViewProjectionOffset(customViewId, project).transact(xas.write) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetView(customView) >>
      assertIO(projectionOffsetCount, 1)
  }

  test("The resetter should create a new view") {
    for {
      createdViewRef <- Ref.of[IO, String]("start")
      _              <- resetWithViewCreation(createdViewRef).resetView(defaultView)
      _              <- assertIO(createdViewRef.get, defaultEsViewId)
    } yield ()
  }

  test("The resetter should delete the default index") {
    for {
      indexDeletedRef <- Ref.of[IO, IndexLabel](IndexLabel.unsafe("some"))
      _               <- resetWithIndexDeletion(indexDeletedRef).resetView(defaultView)
      _               <- assertIO(indexDeletedRef.get, defaultIndexLabel)
    } yield ()
  }

  test("The resetter should delete should handle all default views") {
    clearDB.transact(xas.write) >>
      insertViewEvent(defaultEsViewId, project).transact(xas.write) >>
      insertViewEvent(defaultEsViewId, project2).transact(xas.write) >>
      assertIO(eventsCount, 2) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(eventsCount, 0)
  }

  private val defaultViewValue: IndexingElasticSearchViewValue =
    IndexingElasticSearchViewValue(
      name = Some("name"),
      description = Some("description"),
      resourceTag = None,
      pipeline = List.empty,
      mapping = None,
      settings = None,
      context = None,
      permission = permissions.query
    )

  private val viewElem1 = Elem.SuccessElem(
    tpe = EntityType("elasticsearch"),
    id = defaultViewId,
    project = Some(defaultView.ref.project),
    instant = Instant.EPOCH,
    offset = Offset.start,
    value = defaultView,
    rev = 1
  )
  private val viewElem2 =
    viewElem1.copy(project = Some(project2), value = defaultView.copy(ref = ViewRef(project2, iri"$defaultEsViewId")))

  val viewStream: SuccessElemStream[IndexingViewDef] = Stream(viewElem1, viewElem2)

  private lazy val resetWithNoViewCreation = ElasticSearchDefaultViewsResetter(
    viewStream,
    _ => IO(true),
    (_, _, _) => IO.unit,
    defaultViewValue,
    IO(true),
    xas
  )

  private def resetWithViewCreation(created: Ref[IO, String]) =
    ElasticSearchDefaultViewsResetter(
      viewStream,
      _ => IO(true),
      (id, _, _) => created.set(id.toString) >> IO.unit,
      defaultViewValue,
      IO(true),
      xas
    )

  private def resetWithIndexDeletion(index: Ref[IO, IndexLabel]) =
    ElasticSearchDefaultViewsResetter(
      viewStream,
      idx => index.set(idx) >> IO(true),
      (_, _, _) => IO.unit,
      defaultViewValue,
      IO(true),
      xas
    )

  private def insertViewEvent(id: String, projectRef: ProjectRef) =
    sql"""
       INSERT INTO scoped_events (type, org, project, id, rev, value, instant)
       VALUES ('elasticsearch', ${projectRef.organization}, ${projectRef.project}, $id, 5, '{"nb": 1}', CURRENT_TIMESTAMP);
     """.stripMargin.update.run

  private def insertViewState(id: String, projectRef: ProjectRef) =
    sql"""
       INSERT INTO scoped_states (type, org, project, id, tag, rev, value, deprecated, instant)
       VALUES ('elasticsearch', ${projectRef.organization}, ${projectRef.project}, $id, 'tag', 5, '{"nb": 1}', false, CURRENT_TIMESTAMP);
     """.stripMargin.update.run

  private def insertViewProjectionOffset(id: String, projectRef: ProjectRef) =
    sql"""
       INSERT INTO projection_offsets (name, module, project, resource_id, ordering, processed, discarded, failed, created_at, updated_at)
       VALUES ('default', 'elasticsearch', $projectRef, $id, 123, 2, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
     """.stripMargin.update.run

  private def clearDB =
    sql"""
         DELETE FROM scoped_events; DELETE FROM scoped_states; DELETE FROM projection_offsets;
       """.stripMargin.update.run

  private def eventsCount =
    sql"""SELECT count(*) FROM scoped_events;""".stripMargin
      .query[Int]
      .unique
      .transact(xas.read)

  private def statesCount =
    sql"""SELECT count(*) FROM scoped_states;""".stripMargin
      .query[Int]
      .unique
      .transact(xas.read)

  private def projectionOffsetCount =
    sql"""SELECT count(*) FROM projection_offsets;""".stripMargin
      .query[Int]
      .unique
      .transact(xas.read)

}
