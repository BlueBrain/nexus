package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId, ViewProjectionId}

import java.time.Instant

/**
  * Progression progress for a given view
  * @param offset the offset which has been reached
  * @param processed the number of processed messages
  * @param discarded the number of discarded messages
  * @param failed    the number of failed messages
  */
final case class ProjectionProgress(
    offset: Offset,
    timestamp: Instant,
    processed: Long,
    discarded: Long,
    failed: Long
) {

  /**
    * Takes a new message in account for the progress
    */
  def +(message: Message[_]): ProjectionProgress =
    message match {
      case _: DiscardedMessage => copy(offset = message.offset, processed = processed + 1, discarded = discarded + 1)
      case _: ErrorMessage     => copy(offset = message.offset, processed = processed + 1, failed = failed + 1)
      case _                   => copy(offset = message.offset, processed = processed + 1)
    }
}

/**
  * Projection for a composite view
  * @param id id
  * @param sourceProgress progress for the different sources
  * @param viewProgress progress for the different views
  */
final case class CompositeProjectionProgress(
    id: ViewProjectionId,
    sourceProgress: Map[SourceProjectionId, ProjectionProgress],
    viewProgress: Map[CompositeViewProjectionId, ProjectionProgress]
)

object ProjectionProgress {

  /**
    * When no progress has been done yet
    */
  val NoProgress: ProjectionProgress = ProjectionProgress(NoOffset, Instant.EPOCH, 0L, 0L, 0L)

}
