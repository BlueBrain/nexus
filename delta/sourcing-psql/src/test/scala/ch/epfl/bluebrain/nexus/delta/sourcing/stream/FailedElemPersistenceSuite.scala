package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem._
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import monix.bio.UIO

import java.time.Instant
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration.DurationInt

class FailedElemPersistenceSuite extends BioSuite {

  implicit private val batch: BatchConfig             = BatchConfig(2, 10.millis)
  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  private val projection1 = ProjectionMetadata("test", "name1", None, None)

  private def failureStream =
    (_: Offset) =>
      Stream
        .range(1, 11)
        .map { value =>
          FailedElem(EntityType("entity"), "id", Instant.EPOCH, Offset.at(value.toLong), new RuntimeException("boom"))
        }

  private def successStream =
    (_: Offset) =>
      Stream
        .range(1, 11)
        .map { value => SuccessElem(EntityType("entity"), "id", Instant.EPOCH, Offset.at(value.toLong), ()) }

  private val saveFailedElems: MutableSet[FailedElem] => List[FailedElem] => UIO[Unit] =
    failedElemStore => failedElems => UIO.delay { failedElems.foreach(failedElemStore.add) }

  private val cpPersistentNodeFailures  =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.PersistentSingleNode, failureStream)
  private val cpEveryNodeFailures       =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.TransientSingleNode, failureStream)
  private val cpPersistentNodeSuccesses =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.PersistentSingleNode, successStream)

  private val expectedProgress: ProjectionProgress = ProjectionProgress(
    Offset.at(10L),
    Instant.EPOCH,
    processed = 10,
    discarded = 0,
    failed = 10
  )

  test("FailedElems are saved (persistent single node)") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpPersistentNodeFailures, UIO.none, _ => UIO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = assertEquals(failedElems.size, 10)
      _           = projection.currentProgress.assert(expectedProgress)
    } yield ()
  }

  test("FailedElems are saved (transient single node)") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpEveryNodeFailures, UIO.none, _ => UIO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = assertEquals(failedElems.size, 10)
      _           = projection.currentProgress.assert(expectedProgress)
    } yield ()
  }

  test("Success stream saves no FailedElems") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpPersistentNodeSuccesses, UIO.none, _ => UIO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = failedElems.assertEmpty()
    } yield ()
  }

}
