package ch.epfl.bluebrain.nexus.ship

import cats.Show
import cats.kernel.Monoid
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.ImportReport.Count
import ch.epfl.bluebrain.nexus.ship.model.InputEvent

import java.time.Instant

final case class ImportReport(offset: Offset, instant: Instant, progress: Map[EntityType, Count]) {
  def +(event: InputEvent, status: ImportStatus): ImportReport = {
    val entityType  = event.`type`
    val newProgress = progress.updatedWith(entityType) {
      case Some(count) => Some(count |+| status.asCount)
      case None        => Some(status.asCount)
    }
    copy(offset = event.ordering, instant = event.instant, progress = newProgress)
  }

  def aggregatedCount: Count = {
    progress.values.reduceOption(_ |+| _).getOrElse(Count(0L, 0L))
  }
}

object ImportReport {

  val start: ImportReport = ImportReport(Offset.start, Instant.EPOCH, Map.empty)

  final case class Count(success: Long, dropped: Long)

  object Count {

    implicit val countMonoid: Monoid[Count] = new Monoid[Count] {
      override def empty: Count = Count(0L, 0L)

      override def combine(x: Count, y: Count): Count = Count(x.success + y.success, x.dropped + y.dropped)
    }

  }

  implicit val showReport: Show[ImportReport] = (report: ImportReport) => {
    val header  = s"Type\tSuccess\tDropped\n"
    val details = report.progress.foldLeft(header) { case (acc, (entityType, count)) =>
      acc ++ s"$entityType\t${count.success}\t${count.dropped}\n"
    }

    val aggregatedCount = report.aggregatedCount
    val global          =
      s"${aggregatedCount.success} events were imported up to offset ${report.offset} (${aggregatedCount.dropped} have been dropped)."
    s"$global\n$details"
  }
}
