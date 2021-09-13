package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.Semigroup
import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Codec, Decoder, Encoder}

import java.time.Instant
import scala.annotation.nowarn
import scala.math.Ordering.Implicits._

/**
  * The counts (with its instant) for all the projects
  */
final case class ProjectCountsCollection(value: Map[ProjectRef, ProjectCount]) extends AnyVal {

  /**
    * Attempts to fetch the counts for a single project
    *
    * @param projectRef
    *   the project reference
    */
  def get(projectRef: ProjectRef): Option[ProjectCount] =
    value.get(projectRef)

  /**
    * Increments the counts for the passed project ''projectRef''. The remaining instant on that project will be the
    * latest, between the already existing and the passed ''instant''. The remaining event count will be the current
    * event count (if some) + 1 The remaining resources count will be the current resources count (if some) + 1 (when
    * rev == 1) or + 0 (when rev > 1)
    */
  def increment(projectRef: ProjectRef, rev: Long, instant: Instant): ProjectCountsCollection = {
    val resourcesIncrement = if (rev == 1L) 1L else 0L
    ProjectCountsCollection(value |+| Map(projectRef -> ProjectCount(1, resourcesIncrement, instant)))
  }
}

object ProjectCountsCollection {

  /**
    * An empty [[ProjectCountsCollection]]
    */
  val empty: ProjectCountsCollection = ProjectCountsCollection(Map.empty)

  /**
    * The counts for a single project
    *
    * @param events
    *   the number of events existing on the project
    * @param resources
    *   the number of resources existing on the project
    * @param lastProcessedEventDateTime
    *   the time when the last count entry was created
    */
  final case class ProjectCount(events: Long, resources: Long, lastProcessedEventDateTime: Instant)

  object ProjectCount {

    val emptyEpoch: ProjectCount = ProjectCount(0, 0, Instant.EPOCH)

    implicit val projectCountSemigroup: Semigroup[ProjectCount] =
      (x: ProjectCount, y: ProjectCount) =>
        ProjectCount(
          x.events + y.events,
          x.resources + y.resources,
          x.lastProcessedEventDateTime.max(y.lastProcessedEventDateTime)
        )

    implicit val projectCountCodec: Codec[ProjectCount] = {
      @nowarn("cat=unused")
      implicit val config: Configuration = Configuration.default.copy(
        transformMemberNames = {
          case "events"    => "eventsCount"
          case "resources" => "resourcesCount"
          case other       => other
        }
      )
      deriveConfiguredCodec[ProjectCount]
    }

    implicit val projectCountJsonLdEncoder: JsonLdEncoder[ProjectCount] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))
  }

  implicit val projectsCountsMonoid: Monoid[ProjectCountsCollection] =
    new Monoid[ProjectCountsCollection] {
      override def empty: ProjectCountsCollection = ProjectCountsCollection.empty

      override def combine(
          x: ProjectCountsCollection,
          y: ProjectCountsCollection
      ): ProjectCountsCollection =
        ProjectCountsCollection(x.value |+| y.value)
    }

  implicit val projectsCountsEncoder: Encoder[ProjectCountsCollection] =
    Encoder.encodeMap[ProjectRef, ProjectCount].contramap(_.value)

  implicit val projectsCountsDecoder: Decoder[ProjectCountsCollection] =
    Decoder.decodeMap[ProjectRef, ProjectCount].map(ProjectCountsCollection(_))
}
