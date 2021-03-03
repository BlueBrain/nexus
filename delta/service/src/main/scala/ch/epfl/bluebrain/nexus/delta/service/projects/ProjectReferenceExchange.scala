package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectEvent, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ReferenceExchange}
import io.circe.syntax.EncoderOps
import monix.bio.UIO

/**
  * Project specific [[ReferenceExchange]] implementation for handling indexing of projects alongside its resources.
  *
  * @param projects the projects module
  */
class ProjectReferenceExchange(projects: Projects) extends ReferenceExchange {

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
              .map { res => Some(ReferenceExchangeValue(res, res.value.source.asJson)) }
              .onErrorHandle((_: ProjectNotFound) => None)
          case _                   => UIO.pure(None)
        }
    }
}
