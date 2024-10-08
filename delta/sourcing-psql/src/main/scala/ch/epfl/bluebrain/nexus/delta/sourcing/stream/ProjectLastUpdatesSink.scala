package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{IO, Ref}
import cats.implicits.catsSyntaxPartialOrder
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CollectionUtils.quote
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectLastUpdateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate.ProjectLastUpdateMap
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectLastUpdatesSink.logger
import fs2.Chunk
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * Sink that computes new project last updates from the elem stream and push them to the database
  * @param store
  *   the store to insert/update to the database
  * @param mapRef
  *   a in-memory representation of the values of all the project This value is populated from the database at startup
  *   and kept in sync with the db while processing elems
  * @param chunkSize
  *   the maximum number of elems to be processed at once
  * @param maxWindow
  *   the maximum window before the new values are pushed
  */
final class ProjectLastUpdatesSink(
    store: ProjectLastUpdateStore,
    mapRef: Ref[IO, ProjectLastUpdateMap],
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
) extends Sink {
  {}

  override type In = Unit

  override def inType: Typeable[Unit] = Typeable[Unit]

  override def apply(elements: Chunk[Elem[Unit]]): IO[Chunk[Elem[Unit]]] = {
    for {
      map    <- mapRef.get
      updates = computeUpdates(elements, map)
      _      <- store.save(updates.values.toList)
      _      <- mapRef.update(_ ++ updates)
      _      <- logger.debug(s"Last updates have been computed for projects: ${quote(updates.keySet)}")
    } yield elements
  }

  private def computeUpdates(
      elements: Chunk[Elem[Unit]],
      map: Map[ProjectRef, ProjectLastUpdate]
  ): Map[ProjectRef, ProjectLastUpdate] =
    elements.foldLeft(Map.empty[ProjectRef, ProjectLastUpdate]) { case (acc, elem) =>
      val candidate = ProjectLastUpdate(elem.project, elem.instant, elem.offset)
      map.get(candidate.project) match {
        case Some(current) if current.lastOrdering > candidate.lastOrdering => acc
        case Some(_)                                                        => acc.updated(candidate.project, candidate)
        case None                                                           => acc.updated(candidate.project, candidate)
      }
    }
}

object ProjectLastUpdatesSink {

  private val logger = Logger[ProjectLastUpdatesSink]

  def apply(store: ProjectLastUpdateStore, chunkSize: Int, maxWindow: FiniteDuration): IO[ProjectLastUpdatesSink] =
    Ref.ofEffect(store.fetchAll).map { mapRef =>
      new ProjectLastUpdatesSink(store, mapRef, chunkSize, maxWindow)
    }

}
