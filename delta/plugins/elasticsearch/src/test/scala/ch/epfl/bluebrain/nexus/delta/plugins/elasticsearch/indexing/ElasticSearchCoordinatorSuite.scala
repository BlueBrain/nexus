package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
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

  private lazy val (sv, projectionStore) = supervisor()
  private val project                    = ProjectRef.unsafe("org", "proj")
  private val id1                        = nxv + "view1"
  private val view1                      = ActiveViewDef(
    ViewRef(project, id1),
    projection = id1.toString,
    None,
    None,
    index = IndexLabel.unsafe("view1"),
    mapping = jobj"""{"properties": { }}""",
    settings = jobj"""{"analysis": { }}""",
    None
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
    None
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
    None
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
    None
  )
  private val resumeSignal    = SignallingRef[Task, Boolean](false).runSyncUnsafe()

  // Streams 4 elements until signal is set to true and then a failed item, 1 updated view and 1 deprecated view
  private def viewStream: ElemStream[IndexingViewDef] =
    Stream(
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view1.ref.viewId,
        instant = Instant.EPOCH,
        offset = Offset.at(1L),
        value = view1
      ),
      DroppedElem(
        tpe = ElasticSearchViews.entityType,
        id = "dropped",
        Instant.EPOCH,
        Offset.at(2L)
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view2.ref.viewId,
        instant = Instant.EPOCH,
        offset = Offset.at(3L),
        value = view2
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = view3.ref.viewId,
        instant = Instant.EPOCH,
        offset = Offset.at(4L),
        value = view3
      )
    ) ++ Stream.never[Task].interruptWhen(resumeSignal) ++ Stream(
      FailedElem(
        tpe = ElasticSearchViews.entityType,
        id = "failed",
        Instant.EPOCH,
        Offset.at(5L),
        new IllegalStateException("Something got wrong :(")
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = deprecatedView1.ref.viewId,
        instant = Instant.EPOCH,
        offset = Offset.at(6L),
        value = deprecatedView1
      ),
      SuccessElem(
        tpe = ElasticSearchViews.entityType,
        id = updatedView2.ref.viewId,
        instant = Instant.EPOCH,
        offset = Offset.at(7L),
        value = updatedView2
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
      _ <- projectionStore.offset(view1.projection).assertSome(expectedViewProgress)
      _  = assert(createdIndices.contains(view1.index), s"The index for '${view1.ref.viewId}' should have been created.")
    } yield ()
  }

  test("View 2 processed all items and completed too") {
    for {
      _ <- sv.describe(view2.projection)
             .map(_.map(_.status))
             .eventuallySome(ExecutionStatus.Completed)
      _ <- projectionStore.offset(view2.projection).assertSome(expectedViewProgress)
      _  = assert(createdIndices.contains(view2.index), s"The index for '${view2.ref.viewId}' should have been created.")
    } yield ()
  }

  test("View 3 is invalid so it should not be started") {
    for {
      _ <- sv.describe(view3.projection).assertNone
      _ <- projectionStore.offset(view3.projection).assertNone
      _  = assert(
             !createdIndices.contains(view3.index),
             s"The index for '${view2.ref.viewId}' should not have been created."
           )
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
      _ <- projectionStore.offset(view1.projection).assertNone
      _  = assert(deletedIndices.contains(view1.index), s"The index for '${view1.ref.viewId}' should have been deleted.")
    } yield ()
  }

  test(
    "View 2 is updated so the previous projection should be stopped, the previous progress and the index should be deleted."
  ) {
    for {
      _ <- sv.describe(view2.projection).eventuallyNone
      _ <- projectionStore.offset(view2.projection).assertNone
      _  = assert(deletedIndices.contains(view2.index), s"The index for '${view2.ref.viewId}' should have been deleted.")
    } yield ()
  }

  test("Updated view 2 processed all items and completed") {
    for {
      _ <- sv.describe(updatedView2.projection)
             .map(_.map(_.status))
             .eventuallySome(ExecutionStatus.Completed)
      _ <- projectionStore.offset(updatedView2.projection).assertSome(expectedViewProgress)
      _  = assert(
             createdIndices.contains(updatedView2.index),
             s"The new index for '${updatedView2.ref.viewId}' should have been created."
           )
    } yield ()
  }

}
