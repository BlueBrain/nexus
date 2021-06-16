package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.{Decoder, Encoder}

import java.time.Instant

/**
  * The instants for all the projects
  */
final case class ProjectsEventsInstantCollection(value: Map[ProjectRef, Instant])
object ProjectsEventsInstantCollection {

  val empty: ProjectsEventsInstantCollection = ProjectsEventsInstantCollection(Map.empty)

  implicit val projectsEventsInstantCollectionEncoder: Encoder[ProjectsEventsInstantCollection] =
    Encoder.encodeMap[ProjectRef, Instant].contramap(_.value)

  implicit val projectsEventsInstantCollectionDecoder: Decoder[ProjectsEventsInstantCollection] =
    Decoder.decodeMap[ProjectRef, Instant].map(ProjectsEventsInstantCollection(_))
}
