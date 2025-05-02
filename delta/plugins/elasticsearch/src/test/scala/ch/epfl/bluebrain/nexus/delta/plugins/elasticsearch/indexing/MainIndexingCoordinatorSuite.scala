package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.MainIndexingCoordinator.ProjectDef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json
import munit.AnyFixture

import java.time.Instant
import scala.collection.mutable.Set as MutableSet
import scala.concurrent.duration.*

class MainIndexingCoordinatorSuite extends NexusSuite with SupervisorSetup.Fixture with Fixtures {

  override def munitFixtures: Seq[AnyFixture[?]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 10.millis)

  implicit val baseUri: BaseUri = BaseUri.unsafe("http://localhost", "v1")

  private lazy val (sv, projections, _) = unapply(supervisor())

  private val project1   = ProjectRef.unsafe("org", "proj1")
  private val project1Id = Projects.encodeId(project1)

  private val project2   = ProjectRef.unsafe("org", "proj2")
  private val project2Id = Projects.encodeId(project1)

  private val resumeSignal = SignallingRef[IO, Boolean](false).unsafeRunSync()

  private def success[A](project: ProjectRef, id: Iri, value: A, offset: Long): Elem[A] =
    SuccessElem(tpe = Projects.entityType, id, project, Instant.EPOCH, Offset.at(offset), value, 1)

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

  private val graphResourceStream = new GraphResourceStream {
    override def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
      PullRequestStream.generate(project)

    override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
      PullRequestStream.generate(project)

    override def remaining(project: ProjectRef, selectFilter: SelectFilter, start: Offset): IO[Option[RemainingElems]] =
      IO.none
  }

  private val expectedProgress = ProjectionProgress(Offset.at(4L), Instant.EPOCH, 4, 1, 1)
  private val createdAliases   = MutableSet.empty[ProjectRef]

  test("Start the coordinator") {
    for {
      _ <- MainIndexingCoordinator(
             _ => projectStream,
             graphResourceStream,
             sv,
             new NoopSink[Json],
             project => IO(createdAliases.add(project)).void
           )
      _ <- sv.describe(MainIndexingCoordinator.metadata.name)
             .map(_.map(_.progress))
             .assertEquals(Some(ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 0, 0)))
             .eventually
    } yield ()
  }

  test(s"Projection for '$project1' processed all items and completed") {
    val projectionName = s"main-indexing-$project1"
    for {
      _ <- sv.describe(projectionName)
             .map(_.map(_.status))
             .assertEquals(Some(ExecutionStatus.Completed))
             .eventually
      _ <- projections.progress(projectionName).assertEquals(Some(expectedProgress))
      _  = assert(createdAliases.contains(project1), s"The alias for $project1 should have been created.")
    } yield ()
  }

  test(s"Projection for '$project2' processed all items and completed too") {
    val projectionName = s"main-indexing-$project2"
    for {
      _ <- sv.describe(projectionName)
             .map(_.map(_.status))
             .assertEquals(Some(ExecutionStatus.Completed))
             .eventually
      _ <- projections.progress(projectionName).assertEquals(Some(expectedProgress))
      _  = assert(createdAliases.contains(project2), s"The alias for $project2 should have been created.")
    } yield ()
  }

  test("Resume the stream of projects") {
    for {
      _ <- resumeSignal.set(true)
      _ <- sv.describe(MainIndexingCoordinator.metadata.name)
             .map(_.map(_.progress))
             .assertEquals(Some(ProjectionProgress(Offset.at(4L), Instant.EPOCH, 4, 0, 0)))
             .eventually
    } yield ()
  }

  test(s"Projection for '$project1' should not be restarted by the new project state.") {
    val projectionName = s"main-indexing-$project1"
    for {
      _ <- sv.describe(projectionName).map(_.map(_.restarts)).assertEquals(Some(0)).eventually
      _ <- projections.progress(projectionName).assertEquals(Some(expectedProgress))
    } yield ()
  }

  test(s"'$project2' is marked for deletion, the associated projection should be destroyed.") {
    val projectionName = s"main-indexing-$project2"
    for {
      _ <- sv.describe(projectionName).assertEquals(None).eventually
      _ <- projections.progress(projectionName).assertEquals(None)
    } yield ()
  }
}
