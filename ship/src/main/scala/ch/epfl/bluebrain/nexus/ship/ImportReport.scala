package ch.epfl.bluebrain.nexus.ship

import cats.Show
import cats.kernel.Monoid
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.ImportReport.Statistics
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import java.time.Instant

final case class ImportReport(offset: Offset, instant: Instant, progress: Map[EntityType, Statistics]) {
  def +(event: RowEvent, status: ImportStatus): ImportReport = {
    val entityType  = event.`type`
    val newProgress = progress.updatedWith(entityType) {
      case Some(count) => Some(count |+| status.asCount)
      case None        => Some(status.asCount)
    }
    copy(offset = event.ordering, instant = event.instant, progress = newProgress)
  }
}

object ImportReport {

  val start: ImportReport = ImportReport(Offset.start, Instant.EPOCH, Map.empty)

  final case class Statistics(success: Long, dropped: Long)

  object Statistics {

    implicit val statisticsMonoid: Monoid[Statistics] = new Monoid[Statistics] {
      override def empty: Statistics = Statistics(0L, 0L)

      override def combine(x: Statistics, y: Statistics): Statistics =
        Statistics(x.success + y.success, x.dropped + y.dropped)
    }

    implicit val statisticsEncoder: Encoder[Statistics] = deriveEncoder[Statistics]

  }

  implicit val reportEncoder: Encoder[ImportReport] = deriveEncoder[ImportReport]

  implicit val showReport: Show[ImportReport] = (report: ImportReport) => {
    val details = report.progress.foldLeft("Details: ") { case (acc, (entityType, count)) =>
      acc ++ s"$entityType\t${count.success}\t${count.dropped} ,"
    }

    val offsetValue     = report.offset.value
    val aggregatedCount = report.progress.values.reduceOption(_ |+| _).getOrElse(Statistics(0L, 0L))
    val global          =
      s"${aggregatedCount.success} events were imported up to offset/instant $offsetValue / ${report.instant} (${aggregatedCount.dropped} have been dropped)."
    s"$global\n$details"
  }
}
