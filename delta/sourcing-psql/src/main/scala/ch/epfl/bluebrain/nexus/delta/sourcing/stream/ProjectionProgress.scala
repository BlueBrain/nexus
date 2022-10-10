package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.InstantInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}

import java.time.Instant

final case class ProjectionProgress(offset: Offset,
                                    instant: Instant,
                                    processed: Long,
                                    discarded: Long,
                                    failed: Long) {

  /**
    * Takes a new message in account for the progress
    */
  def +(elem: Elem[_]): ProjectionProgress =
    elem match {
      case d: DroppedElem  =>
        copy(
          offset = d.offset,
          instant = d.instant,
          processed = processed + 1,
          discarded = discarded + 1
        )
      case f: FailedElem      =>
        copy(offset = f.offset, instant = f.instant, processed = processed + 1, failed = failed + 1)
      case s: SuccessElem[_] =>
        copy(
          instant = instant.max(s.instant),
          offset = s.offset.max(offset),
          processed = processed + 1
        )
    }

}

object ProjectionProgress {

  /**
    * When no progress has been done yet
    */
  val NoProgress: ProjectionProgress = ProjectionProgress(Start, Instant.EPOCH, 0L, 0L, 0L)

}

