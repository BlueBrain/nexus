package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{ProgressStatus, SingleProgress}
import ch.epfl.bluebrain.nexus.sourcing.projections.instances._
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._

/**
  * Representation of projection progress.
  */
sealed trait ProjectionProgress extends Product with Serializable {

  /**
    * The smallest single progress
    */
  def minProgress: SingleProgress

  /**
    * The smallest single progress from the selected progress ids that match the passed filter ''f''
    *
    * @param f the progressId filter
    */
  def minProgressFilter(f: String => Boolean): SingleProgress

  /**
    * Adds a status
    *
    * @param id       the progress id
    * @param offset   the offset value
    * @param progress the progress status
    */
  def +(id: String, offset: Offset, progress: ProgressStatus): ProjectionProgress

  /**
    * Retrieves a single progress wth the passed id
    *
    * @param id the progress id
    */
  def progress(id: String): SingleProgress

}

object ProjectionProgress {

  private def toLong(b: Boolean) = if (b) 1L else 0L

  type OffsetProgressMap = Map[String, SingleProgress]

  /**
    * Enumeration of the possible progress status for a single message
    */
  sealed trait ProgressStatus extends Product with Serializable {
    def discarded: Boolean = false
    def failed: Boolean    = false
  }

  object ProgressStatus {

    /**
      * A discarded messaged
      */
    final case object Discarded extends ProgressStatus {
      override def discarded: Boolean = true
    }

    /**
      * A failed message
      *
      * @param error the failure
      */
    final case class Failed(error: String) extends ProgressStatus {
      override def failed: Boolean = true
    }

    final case object Passed extends ProgressStatus
  }

  /**
    * Representation of a single projection progress.
    */
  sealed trait SingleProgress extends ProjectionProgress {

    /**
      * The last processed [[Offset]].
      */
    def offset: Offset

    /**
      * Count of processed events.
      */
    def processed: Long

    /**
      * Count of discarded events.
      */
    def discarded: Long

    /**
      * Count of failed events.
      */
    def failed: Long

    def minProgress: SingleProgress = this

    def minProgressFilter(f: String => Boolean): SingleProgress = minProgress

    def progress(id: String): SingleProgress = this

  }

  /**
    * Representation of lack of recorded progress
    */
  final case object NoProgress extends SingleProgress {
    val offset: Offset  = NoOffset
    val processed: Long = 0
    val discarded: Long = 0
    val failed: Long    = 0

    def +(id: String, offset: Offset, progress: ProgressStatus): ProjectionProgress =
      OffsetsProgress(
        Map(id -> OffsetProgress(offset, 1L, toLong(progress.discarded), toLong(progress.failed)))
      )
  }

  /**
    * Representation of [[Offset]] based projection progress.
    */
  final case class OffsetProgress(offset: Offset, processed: Long, discarded: Long, failed: Long)
      extends SingleProgress {
    def +(id: String, newOffset: Offset, progress: ProgressStatus): ProjectionProgress =
      OffsetsProgress(
        Map(
          id -> OffsetProgress(
            newOffset,
            processed + 1L,
            discarded + toLong(progress.discarded),
            failed + toLong(progress.failed)
          )
        )
      )
  }

  /**
    * Representation of multiple [[Offset]] based projection. Each projection has a unique identifier
    * and a [[SingleProgress]]
    */
  final case class OffsetsProgress(progress: OffsetProgressMap) extends ProjectionProgress {

    lazy val minProgress: SingleProgress = minProgressFilter(_ => true)

    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    def minProgressFilter(f: String => Boolean): SingleProgress = {
      val filtered = progress.view.filterKeys(f)
      if (filtered.isEmpty) NoProgress else filtered.values.minBy(_.offset)
    }

    /**
      * Replaces the passed single projection
      */
    def replace(tuple: (String, SingleProgress)): OffsetsProgress =
      OffsetsProgress(progress + tuple)

    def +(id: String, offset: Offset, progress: ProgressStatus): ProjectionProgress = {
      val previous = this.progress.getOrElse(id, NoProgress)
      replace(
        id -> OffsetProgress(
          offset,
          previous.processed + 1L,
          previous.discarded + toLong(progress.discarded),
          previous.failed + toLong(progress.failed)
        )
      )
    }

    def progress(id: String): SingleProgress = progress.getOrElse(id, NoProgress)

  }

  object OffsetsProgress {
    val empty: OffsetsProgress = OffsetsProgress(Map.empty)
  }

  implicit private[projections] val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit final val singleProgressEncoder: Encoder[SingleProgress] = deriveConfiguredEncoder[SingleProgress]
  implicit final val singleProgressDecoder: Decoder[SingleProgress] = deriveConfiguredDecoder[SingleProgress]

  implicit final val mapProgressEncoder: Encoder[OffsetProgressMap] = Encoder.instance { m =>
    val jsons = m.map { case (index, projection) => Json.obj("index" -> index.asJson, "value" -> projection.asJson) }
    Json.arr(jsons.toSeq: _*)
  }

  implicit final val mapProgressDecoder: Decoder[OffsetProgressMap] =
    (hc: HCursor) =>
      hc.value.asArray.toRight(DecodingFailure("Expected array was not found", hc.history)).flatMap { arr =>
        arr.foldM[Decoder.Result, OffsetProgressMap](Map.empty[String, SingleProgress]) { (map, c) =>
          (c.hcursor.get[String]("index"), c.hcursor.get[SingleProgress]("value")).mapN {
            case (index, projection) =>
              map + (index -> projection)
          }
        }
      }

  implicit final val projectionProgressEncoder: Encoder[ProjectionProgress] =
    deriveConfiguredEncoder[ProjectionProgress]
  implicit final val projectionProgressDecoder: Decoder[ProjectionProgress] =
    deriveConfiguredDecoder[ProjectionProgress]

}
