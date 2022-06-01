package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectMarkedForDeletion, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectEvent, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectEventExchange.MarkedForDeletion
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * Project specific [[EventExchange]] implementation for handling indexing of projects alongside its resources.
  *
  * @param projects
  *   the projects module
  */
class ProjectEventExchange(projects: Projects)(implicit base: BaseUri, defaultApiMappings: ApiMappings)
    extends EventExchange {

  override type E = ProjectEvent
  override type A = Project
  override type M = Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[ProjectEvent]] =
    event match {
      case ev: ProjectEvent => Some(JsonValue(ev))
      case _                => None
    }

  override def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case p: ProjectEvent =>
        UIO.some(
          ProjectScopedMetric.from[ProjectEvent](
            p,
            p match {
              case _: ProjectCreated           => EventMetric.Created
              case _: ProjectUpdated           => EventMetric.Updated
              case _: ProjectDeprecated        => EventMetric.Deprecated
              case _: ProjectMarkedForDeletion => MarkedForDeletion
            },
            ResourceUris.project(p.project).accessUri.toIri,
            Set(nxv.Project),
            JsonObject.empty
          )
        )
      case _               => UIO.none
    }

  override def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeValue[A, M]]] =
    (event, tag) match {
      case (ev: ProjectEvent, None) => resourceToValue(projects.fetch(ev.project))
      case _                        => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[ProjectRejection, ProjectResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(
          EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value.metadata))
        )
      }
      .onErrorHandle(_ => None)
}

object ProjectEventExchange {

  /**
    * Specific action for projects
    */
  private val MarkedForDeletion = Label.unsafe("MarkedForDeletion")
}
