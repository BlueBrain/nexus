package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CollectionUtils.quote
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectLastUpdateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectLastUpdatesSink.logger
import fs2.Chunk
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * Sink that computes new project last updates from the elem stream and push them to the database
  * @param store
  *   the store to insert/update to the database
  * @param chunkSize
  *   the maximum number of elems to be processed at once
  * @param maxWindow
  *   the maximum window before the new values are pushed
  */
final class ProjectLastUpdatesSink(
    store: ProjectLastUpdateStore,
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
) extends Sink {
  {}

  override type In = Unit

  override def inType: Typeable[Unit] = Typeable[Unit]

  override def apply(elements: Chunk[Elem[Unit]]): IO[Chunk[Elem[Unit]]] = {
    val updates = computeUpdates(elements)
    for {
      _ <- store.save(updates.values.toList)
      _ <- logger.debug(s"Last updates have been computed for projects: ${quote(updates.keySet)}")
    } yield elements
  }

  private def computeUpdates(
      elements: Chunk[Elem[Unit]]
  ): Map[ProjectRef, ProjectLastUpdate] =
    elements.foldLeft(Map.empty[ProjectRef, ProjectLastUpdate]) { case (acc, elem) =>
      val newValue = ProjectLastUpdate(elem.project, elem.instant, elem.offset)
      acc.updated(newValue.project, newValue)
    }
}

object ProjectLastUpdatesSink {

  private val logger = Logger[ProjectLastUpdatesSink]

  def apply(store: ProjectLastUpdateStore, chunkSize: Int, maxWindow: FiniteDuration): ProjectLastUpdatesSink =
    new ProjectLastUpdatesSink(store, chunkSize, maxWindow)

}
