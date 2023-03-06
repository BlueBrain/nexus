package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * The statistics for a single project
  *
  * @param events
  *   the number of events existing on the project
  * @param resources
  *   the number of resources existing on the project
  * @param lastEventTime
  *   the number of resources existing on the project
  */
final case class ProjectStatistics(events: Long, resources: Long, lastEventTime: Instant)

object ProjectStatistics {

  implicit val projectStatisticsCodec: Codec[ProjectStatistics] = {
    @nowarn("cat=unused")
    implicit val config: Configuration = Configuration.default.copy(
      transformMemberNames = {
        case "events"        => "eventsCount"
        case "resources"     => "resourcesCount"
        case "lastEventTime" => "lastProcessedEventDateTime"
        case other           => other
      }
    )
    deriveConfiguredCodec[ProjectStatistics]
  }

  implicit val projectStatisticsJsonLdEncoder: JsonLdEncoder[ProjectStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))
}
