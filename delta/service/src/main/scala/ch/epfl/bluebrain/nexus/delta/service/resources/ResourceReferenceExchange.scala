package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, ReferenceExchange, Resources}
import monix.bio.{IO, UIO}

/**
  * Resource specific [[ReferenceExchange]] implementation.
  *
  * @param resources the resources module
  */
class ResourceReferenceExchange(resources: Resources) extends ReferenceExchange {

  override type A = Resource

  override def toResource(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(iri, project, None))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resources.fetchAt(iri, project, None, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resources.fetchBy(iri, project, None, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(iri, project, Some(schema)))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resources.fetchAt(iri, project, Some(schema), rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resources.fetchBy(iri, project, Some(schema), tag))
    }

  private def resourceToValue(
      resourceIO: IO[ResourceRejection, DataResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[A]]] =
    resourceIO
      .map(res => Some(ReferenceExchangeValue(res, res.value.source, enc)))
      .onErrorHandle(_ => None)
}
