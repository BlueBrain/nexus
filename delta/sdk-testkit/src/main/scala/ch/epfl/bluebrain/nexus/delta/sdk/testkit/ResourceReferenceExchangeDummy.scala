package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, ReferenceExchange, Resources}
import monix.bio.{IO, UIO}

/**
  * Resource specific [[ReferenceExchange]] dummy implementation.
  *
  * @param resources the resources module
  */
class ResourceReferenceExchangeDummy(resources: Resources) extends ReferenceExchange {

  override type E = ResourceEvent
  override type A = Resource
  override type M = Unit

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Resource, Unit]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(iri, project, None))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resources.fetchAt(iri, project, None, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resources.fetchBy(iri, project, None, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Resource, Unit]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(iri, project, Some(schema)))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resources.fetchAt(iri, project, Some(schema), rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resources.fetchBy(iri, project, Some(schema), tag))
    }

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: ResourceEvent => Some((value.project, value.id))
      case _                    => None
    }

  private def resourceToValue(
      resourceIO: IO[ResourceRejection, DataResource]
  ): UIO[Option[ReferenceExchangeValue[Resource, Unit]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.source)(_ => ())) }
      .onErrorHandle(_ => None)
}
