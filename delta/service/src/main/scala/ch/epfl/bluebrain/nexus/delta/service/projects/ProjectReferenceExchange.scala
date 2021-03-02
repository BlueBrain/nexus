package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectEvent, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ReferenceExchange}
import io.circe.syntax.EncoderOps
import monix.bio.UIO

/**
  * Project specific [[ReferenceExchange]] implementation for handling indexing of projects alongside its resources.
  *
  * @param projects the projects module
  */
class ProjectReferenceExchange(projects: Projects)(implicit baseUri: BaseUri, resolution: RemoteContextResolution)
    extends ReferenceExchange {

  override type E = ProjectEvent
  override type A = Project

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Project]]] =
    UIO.pure(None)

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Project]]] =
    UIO.pure(None)

  override def apply(event: Event): Option[(ProjectRef, IriOrBNode.Iri)] =
    None

  override def apply(event: Event, tag: Option[TagLabel]): UIO[Option[ReferenceExchangeValue[Project]]] =
    tag match {
      case Some(_) => UIO.pure(None) // projects cannot be tagged
      case None    =>
        event match {
          case value: ProjectEvent =>
            projects
              .fetch(value.project)
              .map { res =>
                Some(
                  new ReferenceExchangeValue[Project](
                    toResource = res,
                    toSource = res.value.source.asJson,
                    toGraph = res.value.toGraph,
                    toCompacted = res.toCompactedJsonLd,
                    toExpanded = res.toExpandedJsonLd,
                    toNTriples = res.toNTriples,
                    toDot = res.toDot
                  )
                )
              }
              .onErrorHandle((_: ProjectNotFound) => None)
          case _                   => UIO.pure(None)
        }
    }
}
