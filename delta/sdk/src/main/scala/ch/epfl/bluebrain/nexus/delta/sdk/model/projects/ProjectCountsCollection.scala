package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.Semigroup
import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}

import java.time.Instant
import scala.math.Ordering.Implicits._

/**
  * The counts (with its instant) for all the projects
  */
final case class ProjectCountsCollection(value: Map[ProjectRef, ProjectCount]) extends AnyVal {

  /**
    * Attempts to fetch the counts for a single project
    *
    * @param projectRef the project reference
    */
  def get(projectRef: ProjectRef): Option[ProjectCount] =
    value.get(projectRef)

  /**
    * Increments the counts for the passed project ''projectRef''.
    * The remaining instant on that project will be the latest, between the already existing and the passed ''instant''.
    * The remaining count will be the current count (if some) + 1
    */
  def increment(projectRef: ProjectRef, instant: Instant): ProjectCountsCollection =
    ProjectCountsCollection(value |+| Map(projectRef -> ProjectCount(1, instant)))
}

object ProjectCountsCollection {

  /**
    * An empty [[ProjectCountsCollection]]
    */
  val empty: ProjectCountsCollection = ProjectCountsCollection(Map.empty)

  /**
    * The counts for a single project
    *
    * @param value  the number of events existing on the project
    * @param instant the time when the last count entry was created
    */
  final case class ProjectCount(value: Long, instant: Instant)

  object ProjectCount {

    implicit val projectCountSemigroup: Semigroup[ProjectCount] =
      (x: ProjectCount, y: ProjectCount) => ProjectCount(x.value + y.value, x.instant.max(y.instant))

    implicit val projectCountCodec: Codec[ProjectCount] = deriveCodec[ProjectCount]

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
