package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchDefaultViewsResetter.ViewElement._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
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

  private val defaultIndexLabel = IndexLabel.unsafe("default")
  private val project           = ProjectRef.unsafe("org", "proj")
  private val project2          = ProjectRef.unsafe("org", "proj2")
  private val project3          = ProjectRef.unsafe("org", "proj3")

  private val defaultView = ActiveViewDef(
    ViewRef(project, defaultViewId),
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

  private val customViewId = iri"https://other.id"
  private val customView   = defaultView.copy(ref = ViewRef(project, iri"$customViewId"))

  private val existingDefaultView = ExistingView(defaultView)
  private val missingDefaultView  = MissingView(project3)
  private val existingCustomView  = ExistingView(customView)

  // TODO: Find how to move this to beforeAll
  test("Setup: partitions should be created") {
    initPartitions(xas, project, project2)
  }

  test("The resetter should delete scoped events for an existing default view") {
    insertViewEvent(defaultViewId, project) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetView(existingDefaultView) >>
      assertIO(eventsCount, 0)
  }

  test("The resetter should delete scoped states for an existing default view") {
    insertViewState(defaultViewId, project) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetView(existingDefaultView) >>
      assertIO(statesCount, 0)
  }

  test("The resetter should delete projection offsets for an existing default view") {
    insertViewProjectionOffset(defaultViewId, project) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetView(existingDefaultView) >>
      assertIO(projectionOffsetCount, 0)
  }

  test("The resetter should not delete a scoped event for an existing custom view") {
    insertViewEvent(customViewId, project) >>
      assertIO(eventsCount, 1) >>
      resetWithNoViewCreation.resetView(existingCustomView) >>
      assertIO(eventsCount, 1)
  }

  test("The resetter should not delete a scoped state for an existing custom view") {
    insertViewState(customViewId, project) >>
      assertIO(statesCount, 1) >>
      resetWithNoViewCreation.resetView(existingCustomView) >>
      assertIO(statesCount, 1)
  }

  test("The resetter should not delete projection offsets for a custom view") {
    insertViewProjectionOffset(customViewId, project) >>
      assertIO(projectionOffsetCount, 1) >>
      resetWithNoViewCreation.resetView(existingCustomView) >>
      assertIO(projectionOffsetCount, 1)
  }

  test("The resetter should create a new view") {
    for {
      createdViewRef <- Ref.of[IO, Set[ViewRef]](Set.empty[ViewRef])
      _              <- resetWithViewCreation(createdViewRef).resetView(existingDefaultView)
      _              <- assertIO(createdViewRef.get, Set(defaultView.ref))
    } yield ()
  }

  test("The resetter should delete the default index") {
    for {
      indexDeletedRef <- Ref.of[IO, IndexLabel](IndexLabel.unsafe("some"))
      _               <- resetWithIndexDeletion(indexDeletedRef).resetView(existingDefaultView)
      _               <- assertIO(indexDeletedRef.get, defaultIndexLabel)
    } yield ()
  }

  test("The resetter should delete existing default views and recreate them including the missing one") {
    for {
      createdViewRef      <- Ref.of[IO, Set[ViewRef]](Set.empty[ViewRef])
      _                   <- clearDB
      _                   <- insertViewEvent(defaultViewId, project)
      _                   <- insertViewEvent(defaultViewId, project2)
      _                   <- assertIO(eventsCount, 2)
      _                   <- resetWithViewCreation(createdViewRef).resetDefaultViews
      _                   <- assertIO(eventsCount, 0)
      expectedCreatedViews = Set(project, project2, project3).map(ViewRef(_, defaultViewId))
      _                   <- assertIO(createdViewRef.get, expectedCreatedViews)
    } yield ()
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

  private val viewElem1  = existingDefaultView
  private val viewElem2  = ExistingView(defaultView.copy(ref = ViewRef(project2, defaultViewId)))
  private val viewElem3  = missingDefaultView
  private val viewStream = Stream(viewElem1, viewElem2, viewElem3)

  private def resetWithNoViewCreation = ElasticSearchDefaultViewsResetter(
    viewStream,
    _ => IO(true),
    (_, _, _) => IO.unit,
    defaultViewValue,
    IO(true),
    xas
  )

  private def resetWithViewCreation(created: Ref[IO, Set[ViewRef]]) =
    ElasticSearchDefaultViewsResetter(
      viewStream,
      _ => IO(true),
      (id, project, _) => created.update(_ + ViewRef(project, id)) >> IO.unit,
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

  private def insertViewEvent(id: Iri, projectRef: ProjectRef) =
    sql"""
       INSERT INTO scoped_events (type, org, project, id, rev, value, instant)
       VALUES ('elasticsearch', ${projectRef.organization}, ${projectRef.project}, $id, 5, '{"nb": 1}', CURRENT_TIMESTAMP);
     """.stripMargin.update.run.transact(xas.write)

  private def insertViewState(id: Iri, projectRef: ProjectRef) =
    sql"""
       INSERT INTO scoped_states (type, org, project, id, tag, rev, value, deprecated, instant)
       VALUES ('elasticsearch', ${projectRef.organization}, ${projectRef.project}, $id, 'tag', 5, '{"nb": 1}', false, CURRENT_TIMESTAMP);
     """.stripMargin.update.run.transact(xas.write)

  private def insertViewProjectionOffset(id: Iri, projectRef: ProjectRef) =
    sql"""
       INSERT INTO projection_offsets (name, module, project, resource_id, ordering, processed, discarded, failed, created_at, updated_at)
       VALUES ('default', 'elasticsearch', $projectRef, $id, 123, 2, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
     """.stripMargin.update.run.transact(xas.write)

  private def clearDB =
    sql"""
         DELETE FROM scoped_events; DELETE FROM scoped_states; DELETE FROM projection_offsets;
       """.stripMargin.update.run.transact(xas.write)

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
