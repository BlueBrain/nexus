package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem._
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectSuite, PatienceConfig}
import fs2.Stream

import java.time.Instant
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration.DurationInt

class FailedElemPersistenceSuite extends CatsEffectSuite {

  implicit private val batch: BatchConfig             = BatchConfig(2, 10.millis)
  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  private val projection1 = ProjectionMetadata("test", "name1", None, None)
  private val id          = nxv + "id"
  private val rev         = 1

  private def failureStream =
    (_: Offset) =>
      Stream
        .range(1, 11)
        .map { value =>
          FailedElem(
            EntityType("entity"),
            id,
            None,
            Instant.EPOCH,
            Offset.at(value.toLong),
            new RuntimeException("boom"),
            rev
          )
        }

  private def successStream =
    (_: Offset) =>
      Stream
        .range(1, 11)
        .map { value => SuccessElem(EntityType("entity"), id, None, Instant.EPOCH, Offset.at(value.toLong), (), rev) }

  private val saveFailedElems: MutableSet[FailedElem] => List[FailedElem] => IO[Unit] =
    failedElemStore => failedElems => IO.delay { failedElems.foreach(failedElemStore.add) }

  private val cpPersistentNodeFailures  =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.PersistentSingleNode, failureStream)
  private val cpEveryNodeFailures       =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.EveryNode, failureStream)
  private val cpPersistentNodeSuccesses =
    CompiledProjection.fromStream(projection1, ExecutionStrategy.PersistentSingleNode, successStream)

  test("FailedElems are saved (persistent single node)") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpPersistentNodeFailures, IO.none, _ => IO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = assertEquals(failedElems.size, 10)
    } yield ()
  }

  test("FailedElems are saved (every node)") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpEveryNodeFailures, IO.none, _ => IO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = assertEquals(failedElems.size, 10)
    } yield ()
  }

  test("Success stream saves no FailedElems") {
    val failedElems = MutableSet.empty[FailedElem]
    for {
      projection <- Projection.apply(cpPersistentNodeSuccesses, IO.none, _ => IO.unit, saveFailedElems(failedElems))
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _           = failedElems.assertEmpty()
    } yield ()
  }

}
