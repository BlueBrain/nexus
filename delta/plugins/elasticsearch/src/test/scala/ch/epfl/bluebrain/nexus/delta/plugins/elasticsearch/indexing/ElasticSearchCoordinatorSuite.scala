package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotFindPipeErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration._

class ElasticSearchCoordinatorSuite extends BioSuite with SupervisorSetup.Fixture with CirceLiteral with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private val indexingRev = 1

  private lazy val (sv, projections) = supervisor()
  private val project                = ProjectRef.unsafe("org", "proj")
  private val id1                    = nxv + "view1"
  private val view1                  = ActiveViewDef(
    ViewRef(project, id1),
    projection = id1.toString,
    None,
    None,
    index = IndexLabel.unsafe("view1"),
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    indexingRev
  )

  private val id2   = nxv + "view2"
  private val view2 = ActiveViewDef(
    ViewRef(project, id2),
    projection = id2.toString,
    None,
    None,
    index = IndexLabel.unsafe("view2"),
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    indexingRev
  )

  private val id3         = nxv + "view3"
  private val unknownPipe = PipeRef.unsafe("xxx")
  private val view3       = ActiveViewDef(
    ViewRef(project, id3),
    projection = id3.toString,
    None,
    Some(PipeChain(PipeRef.unsafe("xxx") -> ExpandedJsonLd.empty)),
    index = IndexLabel.unsafe("view3"),
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    indexingRev
  )

  private val deprecatedView1 = DeprecatedViewDef(
    ViewRef(project, id1)
  )
  private val updatedView2    = ActiveViewDef(
    ViewRef(project, id2),
    projection = id2.toString + "_2",
    None,
    None,
    index = IndexLabel.unsafe("view2_2"),
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None,
    indexingRev
  )
  private val resumeSignal    = SignallingRef[Task, Boolean](false).runSyncUnsafe()

  // Streams 4 elements until signal is set to true and then a failed item, 1 updated view and 1 deprecated view
  private def viewStream: ElemStream[IndexingViewDef] =
    Stream(
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view1.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(1L),
        value = view1,
        revision = 1
      ),
      DroppedElem(
        tpe = ElasticSearchViews.entityType,
        id = nxv + "dropped",
        project = Some(project),
        Instant.EPOCH,
        Offset.at(2L),
        revision = 1
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view2.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(3L),
        value = view2,
        revision = 1
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view3.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(4L),
        value = view3,
        revision = 1
      )
    ) ++ Stream.never[Task].interruptWhen(resumeSignal) ++ Stream(
      FailedElem(
        tpe = ElasticSearchViews.entityType,
        id = nxv + "failed_coord",
        project = Some(project),
        Instant.EPOCH,
        Offset.at(5L),
        new IllegalStateException("Something got wrong :("),
        revision = 1
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = deprecatedView1.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(6L),
        value = deprecatedView1,
        revision = 1
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = updatedView2.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(7L),
        value = updatedView2,
        revision = 1
      )
    )

  private val createdIndices                           = MutableSet.empty[IndexLabel]
  private val deletedIndices                           = MutableSet.empty[IndexLabel]
  private val expectedViewProgress: ProjectionProgress = ProjectionProgress(
    Offset.at(4L),
    Instant.EPOCH,
    processed = 4,
    discarded = 1,
    failed = 1
  )

  test("Start the coordinator") {
    for {
      _ <- ElasticSearchCoordinator(
             (_: Offset) => viewStream,
             (project: ProjectRef, _: Tag, _: Offset) => PullRequestStream.generate(project),
             (_: PipeChain) => Left(CouldNotFindPipeErr(unknownPipe)),
             sv,
             (_: ActiveViewDef) => new NoopSink[Json],
             (v: ActiveViewDef) => Task.delay(createdIndices.add(v.index)).void,
             (v: ActiveViewDef) => Task.delay(deletedIndices.add(v.index)).void
           )
      _ <- sv.describe(ElasticSearchCoordinator.metadata.name)
             .map(_.map(_.progress))
             .eventuallySome(ProjectionProgress(Offset.at(4L), Instant.EPOCH, 4, 1, 1))
    } yield ()
  }

  test("View 1 processed all items and completed") {
    for {
      _ <- sv.describe(view1.projection)
             .map(_.map(_.status))
             .eventuallySome(ExecutionStatus.Completed)
      _ <- projections.progress(view1.projection).assertSome(expectedViewProgress)
      _  = assert(createdIndices.contains(view1.index), s"The index for '${view1.ref.viewId}' should have been created.")
    } yield ()
  }

  test("View 2 processed all items and completed too") {
    for {
      _ <- sv.describe(view2.projection)
             .map(_.map(_.status))
             .eventuallySome(ExecutionStatus.Completed)
      _ <- projections.progress(view2.projection).assertSome(expectedViewProgress)
      _  = assert(createdIndices.contains(view2.index), s"The index for '${view2.ref.viewId}' should have been created.")
    } yield ()
  }

  test("View 3 is invalid so it should not be started") {
    for {
      _ <- sv.describe(view3.projection).assertNone
      _ <- projections.progress(view3.projection).assertNone
      _  = assert(
             !createdIndices.contains(view3.index),
             s"The index for '${view3.ref.viewId}' should not have been created."
           )
    } yield ()
  }

  test("There is one error for the coordinator projection before the signal") {
    for {
      entries <- projections.failedElemEntries(ElasticSearchCoordinator.metadata.name, Offset.start).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.failedElemData.id, id3)
    } yield ()
  }

  test("There is one error for view 1") {
    for {
      entries <- projections.failedElemEntries(view1.projection, Offset.start).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.failedElemData.id, nxv + "failed")
      _        = assertEquals(r.failedElemData.entityType, PullRequest.entityType)
      _        = assertEquals(r.failedElemData.offset, Offset.At(4))
    } yield ()
  }

  test("There is one error for view 2") {
    for {
      entries <- projections.failedElemEntries(view2.projection, Offset.start).compile.toList
      _        = entries.assertOneElem
    } yield ()
  }

  test("There are no errors for view 3") {
    for {
      entries <- projections.failedElemEntries(view3.projection, Offset.start).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Resume the stream of view") {
    for {
      _ <- resumeSignal.set(true)
      _ <- sv.describe(ElasticSearchCoordinator.metadata.name)
             .map(_.map(_.progress))
             .eventuallySome(ProjectionProgress(Offset.at(7L), Instant.EPOCH, 7, 1, 2))
    } yield ()
  }

  test("View 1 is deprecated so it is stopped, the progress and the index should be deleted.") {
    for {
      _ <- sv.describe(view1.projection).eventuallyNone
      _ <- projections.progress(view1.projection).assertNone
      _  = assert(deletedIndices.contains(view1.index), s"The index for '${view1.ref.viewId}' should have been deleted.")
    } yield ()
  }

  test(
    "View 2 is updated so the previous projection should be stopped, the previous progress and the index should be deleted."
  ) {
    for {
      _ <- sv.describe(view2.projection).eventuallyNone
      _ <- projections.progress(view2.projection).assertNone
      _  = assert(deletedIndices.contains(view2.index), s"The index for '${view2.ref.viewId}' should have been deleted.")
    } yield ()
  }

  test("Updated view 2 processed all items and completed") {
    for {
      _ <- sv.describe(updatedView2.projection)
             .map(_.map(_.status))
             .eventuallySome(ExecutionStatus.Completed)
      _ <- projections.progress(updatedView2.projection).assertSome(expectedViewProgress)
      _  = assert(
             createdIndices.contains(updatedView2.index),
             s"The new index for '${updatedView2.ref.viewId}' should have been created."
           )
    } yield ()
  }

  test("Coordinator projection should have one error after failed elem offset 4") {
    for {
      entries <- projections.failedElemEntries(ElasticSearchCoordinator.metadata.name, Offset.At(3L)).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.failedElemData.id, nxv + "failed_coord")
    } yield ()
  }

  test("View 2_2 projection should have one error after failed elem offset 4") {
    for {
      entries <- projections.failedElemEntries(updatedView2.projection, Offset.At(4L)).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.failedElemData.id, nxv + "failed")
    } yield ()
  }

}
