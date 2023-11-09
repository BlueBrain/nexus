package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalyticsCoordinator.ProjectDef
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.Noop
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.{GraphAnalyticsResult, GraphAnalyticsStream}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectSuite, PatienceConfig}
import fs2.Stream
import fs2.concurrent.SignallingRef
import munit.AnyFixture

import java.time.Instant
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration._

class GraphAnalyticsCoordinatorSuite extends CatsEffectSuite with SupervisorSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 10.millis)

  private lazy val (sv, projections, _) = unapply(supervisor())
  private val project1                  = ProjectRef.unsafe("org", "proj1")
  private val project1Id                = Projects.encodeId(project1)

  private val project2   = ProjectRef.unsafe("org", "proj2")
  private val project2Id = Projects.encodeId(project1)

  private def success[A](ref: ProjectRef, id: Iri, value: A, offset: Long): Elem[A] =
    SuccessElem(tpe = Projects.entityType, id, Some(ref), Instant.EPOCH, Offset.at(offset), value, 1)

  private def dropped[A](ref: ProjectRef, id: Iri, offset: Long): Elem[A] =
    DroppedElem(tpe = Projects.entityType, id, Some(ref), Instant.EPOCH, Offset.at(offset), 1)

  private def failed[A](ref: ProjectRef, id: Iri, error: Throwable, offset: Long): Elem[A] =
    FailedElem(tpe = Projects.entityType, id, Some(ref), Instant.EPOCH, Offset.at(offset), error, 1)

  private val resumeSignal = SignallingRef[IO, Boolean](false).unsafeRunSync()

  // Stream 2 elements until signal is set to true and then 2 more
  private def projectStream: ElemStream[ProjectDef] =
    Stream(
      success(project1, project1Id, ProjectDef(project1, markedForDeletion = false), 1L),
      success(project2, project2Id, ProjectDef(project2, markedForDeletion = false), 2L)
    ) ++ Stream.never[IO].interruptWhen(resumeSignal) ++
      Stream(
        success(project1, project1Id, ProjectDef(project1, markedForDeletion = false), 3L),
        success(project2, project2Id, ProjectDef(project2, markedForDeletion = true), 4L)
      )

  /**
    * Generates a stream of [[GraphAnalyticsResult]] elems
    */
  private val graphAnalysisStream: GraphAnalyticsStream =
    (project: ProjectRef, _: Offset) =>
      Stream(
        success(project, nxv + s"$project/1", Noop, 1L),
        success(project, nxv + s"$project/2", Noop, 2L),
        dropped(project, nxv + s"$project/3", 3L),
        failed(project, nxv + s"$project/4", new IllegalStateException("BOOM"), 4L)
      )

  private val sink                                         = CacheSink.events[GraphAnalyticsResult]
  private val createdIndices                               = MutableSet.empty[ProjectRef]
  private val deletedIndices                               = MutableSet.empty[ProjectRef]
  private val expectedAnalysisProgress: ProjectionProgress = ProjectionProgress(
    Offset.at(4L),
    Instant.EPOCH,
    processed = 4,
    discarded = 1,
    failed = 1
  )

  test("Start the coordinator") {
    for {
      _ <- GraphAnalyticsCoordinator(
             (_: Offset) => projectStream,
             graphAnalysisStream,
             sv,
             _ => sink,
             (ref: ProjectRef) => IO.delay(createdIndices.add(ref)).void,
             (ref: ProjectRef) => IO.delay(deletedIndices.add(ref)).void
           )
      _ <- sv.describe(GraphAnalyticsCoordinator.metadata.name)
             .map(_.map(_.progress))
             .eventually(Some(ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 0, 0)))
    } yield ()
  }

  test(s"Projection for '$project1' processed all items and completed") {
    val projectionName = s"ga-$project1"
    for {
      _ <- sv.describe(projectionName)
             .map(_.map(_.status))
             .eventually(Some(ExecutionStatus.Completed))
      _ <- projections.progress(projectionName).assertSome(expectedAnalysisProgress)
      _  = assert(createdIndices.contains(project1), s"The index for '$project1' should have been created.")
    } yield ()
  }

  test(s"Projection for '$project2' processed all items and completed too") {
    val projectionName = s"ga-$project2"
    for {
      _ <- sv.describe(projectionName)
             .map(_.map(_.status))
             .eventually(Some(ExecutionStatus.Completed))
      _ <- projections.progress(projectionName).assertSome(expectedAnalysisProgress)
      _  = assert(createdIndices.contains(project2), s"The index for '$project2' should have been created.")
    } yield ()
  }

  test("Resume the stream of projects") {
    for {
      _ <- resumeSignal.set(true)
      _ <- sv.describe(GraphAnalyticsCoordinator.metadata.name)
             .map(_.map(_.progress))
             .eventually(Some(ProjectionProgress(Offset.at(4L), Instant.EPOCH, 4, 0, 0)))
    } yield ()
  }

  test(s"Projection for '$project1' should not be restarted by the new project state.") {
    val projectionName = s"ga-$project1"
    for {
      _ <- sv.describe(projectionName).map(_.map(_.restarts)).eventually(Some(0))
      _ <- projections.progress(projectionName).assertSome(expectedAnalysisProgress)
    } yield ()
  }

  test(s"'$project2' is marked for deletion, the associated projection should be destroyed.") {
    val projectionName = s"ga-$project2"
    for {
      _ <- sv.describe(projectionName).eventually(None)
      _ <- projections.progress(projectionName).assertNone
      _  = assert(deletedIndices.contains(project2), s"The index for '$project2' should have been deleted.")
    } yield ()
  }

}
