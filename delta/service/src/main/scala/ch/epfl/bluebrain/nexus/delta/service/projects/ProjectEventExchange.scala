package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectEvent, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue, ProjectResource, Projects}
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * Project specific [[EventExchange]] implementation for handling indexing of projects alongside its resources.
  *
  * @param projects the projects module
  */
class ProjectEventExchange(projects: Projects)(implicit base: BaseUri) extends EventExchange {

  override type E = ProjectEvent
  override type A = Project
  override type M = Metadata

  override def toJsonLdEvent(event: Event): Option[Aux[ProjectEvent]] =
    event match {
      case ev: ProjectEvent => Some(JsonLdValue(ev))
      case _                => None
    }

  override def toLatestResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    (event, tag) match {
      case (ev: ProjectEvent, None) => resourceToValue(projects.fetch(ev.project))
      case _                        => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[ProjectRejection, ProjectResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value.metadata)))
      }
      .onErrorHandle(_ => None)
}
