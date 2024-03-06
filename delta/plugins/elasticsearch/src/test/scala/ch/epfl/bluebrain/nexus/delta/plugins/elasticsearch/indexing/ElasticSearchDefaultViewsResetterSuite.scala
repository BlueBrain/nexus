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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
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

  private val existingDefaultView = ExistingView(defaultView)
  private val missingDefaultView  = MissingView(project3)

  // TODO: Find how to move this to beforeAll
  test("Setup: partitions should be created") {
    initPartitions(xas, project, project2)
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
      _                   <- insertView(defaultViewId, project)
      _                   <- insertView(defaultViewId, project2)
      _                   <- insertView(customViewId, project)
      expectedViews        = Set(project -> defaultViewId, project2 -> defaultViewId, project -> customViewId)
      _                   <- assertIO(eventsId, expectedViews)
      _                   <- assertIO(statesId, expectedViews)
      _                   <- assertIO(projectionOffsetIds, expectedViews)
      _                   <- resetWithViewCreation(createdViewRef).resetDefaultViews
      remainingCustom      = Set(project -> customViewId)
      _                   <- assertIO(eventsId, remainingCustom)
      _                   <- assertIO(statesId, remainingCustom)
      _                   <- assertIO(projectionOffsetIds, remainingCustom)
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

  private def insertView(id: Iri, projectRef: ProjectRef) =
    insertViewEvent(id, projectRef) >> insertViewState(id, projectRef) >> insertViewProjectionOffset(id, projectRef)

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

  private def insertViewProjectionOffset(id: Iri, projectRef: ProjectRef) = {
    val projectionName = s"${projectRef}_$id"
    sql"""
       INSERT INTO projection_offsets (name, module, project, resource_id, ordering, processed, discarded, failed, created_at, updated_at)
       VALUES ($projectionName, 'elasticsearch', $projectRef, $id, 123, 2, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
     """.stripMargin.update.run.transact(xas.write)
  }

  private def clearDB =
    sql"""
         DELETE FROM scoped_events; DELETE FROM scoped_states; DELETE FROM projection_offsets;
       """.stripMargin.update.run.transact(xas.write)

  private def eventsId: IO[Set[(ProjectRef, Iri)]] =
    sql"""SELECT org, project, id FROM scoped_events;""".stripMargin
      .query[(Label, Label, Iri)]
      .map { case (org, proj, id) => ProjectRef(org, proj) -> id }
      .to[Set]
      .transact(xas.read)

  private def statesId: IO[Set[(ProjectRef, Iri)]] =
    sql"""SELECT org, project, id FROM scoped_states;""".stripMargin
      .query[(Label, Label, Iri)]
      .map { case (org, proj, id) => ProjectRef(org, proj) -> id }
      .to[Set]
      .transact(xas.read)

  private def projectionOffsetIds: IO[Set[(ProjectRef, Iri)]] =
    sql"""SELECT project, resource_id FROM projection_offsets;""".stripMargin
      .query[(ProjectRef, Iri)]
      .to[Set]
      .transact(xas.read)

}
