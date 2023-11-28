package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import fs2.Stream
import munit.AnyFixture

class ElasticSearchDefaultViewsResetterSuite
    extends NexusSuite
    with Doobie.Fixture
    with ElasticSearchClientSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)
  private lazy val xas                           = doobie()

  private val defaultEsViewId   = "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"
  private val defaultIndexLabel = IndexLabel.unsafe("default")
  private val project           = ProjectRef.unsafe("org", "proj")

  test("The reseter should delete scoped events for the default view") {
    initPartitions(xas, project) >>
      insertViewEvent(defaultEsViewId).transact(xas.write) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(eventsCount, 0)
  }

  test("The reseter should delete scoped states for the default view") {
    insertViewState(defaultEsViewId).transact(xas.write) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(statesCount, 0)
  }

  test("The reseter should delete projection offsets for the default view") {
    insertViewProjectionOffset(defaultEsViewId).transact(xas.write) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(projectionOffsetCount, 0)
  }

  test("The reseter should not delete a scoped event for a custom view") {
    insertViewEvent("other").transact(xas.write) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(eventsCount, 1)
  }

  test("The reseter should not delete a scoped state for a custom view") {
    insertViewState("other").transact(xas.write) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(statesCount, 1)
  }

  test("The reseter should not delete projection offsets for a custom view") {
    insertViewProjectionOffset("other").transact(xas.write) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetDefaultViews >>
      assertIO(projectionOffsetCount, 1)
  }

  test("The reseter should create a new view") {
    for {
      createdViewRef <- Ref.of[IO, String]("start")
      _              <- resetWithViewCreation(createdViewRef).resetDefaultViews
      _              <- assertIO(createdViewRef.get, defaultEsViewId)
    } yield ()
  }

  test("The reseter should delete the default index") {
    for {
      indexDeletedRef <- Ref.of[IO, IndexLabel](IndexLabel.unsafe("some"))
      _               <- resetWithIndexDeletion(indexDeletedRef).resetDefaultViews
      _               <- assertIO(indexDeletedRef.get, defaultIndexLabel)
    } yield ()
  }

  private val id = iri"""https://bbp.epfl.ch/nexus"""

  private val view = ActiveViewDef(
    ViewRef(project, id),
    projection = id.toString,
    None,
    SelectFilter.latest,
    index = defaultIndexLabel,
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    IndexingRev.init,
    1
  )

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

  private val fetchIndexingView: (IdSegmentRef, ProjectRef) => IO[ActiveViewDef] = (_, _) => IO.pure(view)
  private val projects: Stream[IO, ProjectRef]                                   = Stream.emit(ProjectRef.unsafe("org", "proj"))

  private lazy val resetWithNoViewCreation = ElasticSearchDefaultViewsResetter(
    _ => IO(true),
    fetchIndexingView,
    (_, _, _) => IO.unit,
    projects,
    defaultViewValue,
    IO(true),
    xas
  )

  private def resetWithViewCreation(created: Ref[IO, String]) =
    ElasticSearchDefaultViewsResetter(
      _ => IO(true),
      fetchIndexingView,
      (id, _, _) => created.set(id.toString) >> IO.unit,
      projects,
      defaultViewValue,
      IO(true),
      xas
    )

  private def resetWithIndexDeletion(index: Ref[IO, IndexLabel]) =
    ElasticSearchDefaultViewsResetter(
      idx => index.set(idx) >> IO(true),
      fetchIndexingView,
      (_, _, _) => IO.unit,
      projects,
      defaultViewValue,
      IO(true),
      xas
    )

  private def insertViewEvent(id: String) =
    sql"""
       INSERT INTO scoped_events (type, org, project, id, rev, value, instant)
       VALUES ('elasticsearch', 'org', 'proj', $id, 5, '{"nb": 1}', CURRENT_TIMESTAMP);
     """.stripMargin.update.run

  private def insertViewState(id: String) =
    sql"""
       INSERT INTO scoped_states (type, org, project, id, tag, rev, value, deprecated, instant)
       VALUES ('elasticsearch', 'org', 'proj', $id, 'tag', 5, '{"nb": 1}', false, CURRENT_TIMESTAMP);
     """.stripMargin.update.run

  private def insertViewProjectionOffset(id: String) =
    sql"""
       INSERT INTO projection_offsets (name, module, project, resource_id, ordering, processed, discarded, failed, created_at, updated_at)
       VALUES ('default', 'elasticsearch', 'org/proj', $id, 123, 2, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
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
