package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import akka.persistence.query.{Offset, Sequence, TimeBasedUUID}
import cats.Semigroup
import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectStatisticsCollection.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.instances._
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto._

import scala.math.Ordering.Implicits._

/**
  * The statistics for all the projects
  */
final case class ProjectStatisticsCollection(value: Map[ProjectRef, ProjectStatistics]) extends AnyVal {

  /**
    * Attempts to fetch the statistics for a single project
    *
    * @param projectRef the project reference
    */
  def get(projectRef: ProjectRef): Option[ProjectStatistics] =
    value.get(projectRef)

  /**
    * Increments the statistics for the passed project ''projectRef''.
    * The remaining offset on that project will be the latest, between the already existing and the passed ''offset''.
    * The remaining count will be the current count (if some) + 1
    */
  def incrementCount(projectRef: ProjectRef, offset: Offset): ProjectStatisticsCollection =
    ProjectStatisticsCollection(value |+| Map(projectRef -> ProjectStatistics(1, offset)))
}

object ProjectStatisticsCollection {

  /**
    * An empty [[ProjectStatisticsCollection]]
    */
  val empty: ProjectStatisticsCollection = ProjectStatisticsCollection(Map.empty)

  /**
    * The statistics for a single project
    *
    * @param count  the number of events existing on the project
    * @param offset the latest offset available
    */
  final case class ProjectStatistics(count: Long, offset: Offset)

  object ProjectStatistics {

    implicit val projectStatisticsSemigroup: Semigroup[ProjectStatistics] =
      (x: ProjectStatistics, y: ProjectStatistics) => {
        val latestOffset = (x.offset, y.offset) match {
          case (xSeq: Sequence, ySeq: Sequence)             => xSeq max ySeq
          case (xTime: TimeBasedUUID, yTime: TimeBasedUUID) => xTime max yTime
          case (xOffset, _)                                 => xOffset // This never happens
        }
        ProjectStatistics(x.count + y.count, latestOffset)
      }

    implicit val projectStatisticsCodec: Codec[ProjectStatistics] = deriveCodec[ProjectStatistics]
  }

  implicit val projectStatisticsCollectionMonoid: Monoid[ProjectStatisticsCollection] =
    new Monoid[ProjectStatisticsCollection] {
      override def empty: ProjectStatisticsCollection = ProjectStatisticsCollection.empty

      override def combine(
          x: ProjectStatisticsCollection,
          y: ProjectStatisticsCollection
      ): ProjectStatisticsCollection =
        ProjectStatisticsCollection(x.value |+| y.value)
    }

  implicit val projectStatisticsCollectionEncoder: Encoder[ProjectStatisticsCollection] =
    Encoder.encodeMap[ProjectRef, ProjectStatistics].contramap(_.value)

  implicit val projectStatisticsCollectionDecoder: Decoder[ProjectStatisticsCollection] =
    Decoder.decodeMap[ProjectRef, ProjectStatistics].map(ProjectStatisticsCollection(_))
}
