package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, ReferenceExchange, Resources}
import monix.bio.{IO, UIO}

/**
  * Resource specific [[ReferenceExchange]] implementation.
  *
  * @param resources the resources module
  */
class ResourceReferenceExchange(resources: Resources)(implicit baseUri: BaseUri, resolution: RemoteContextResolution)
    extends ReferenceExchange {

  override type A = Resource

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Resource]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(IriSegment(iri), project, None))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resources.fetchAt(IriSegment(iri), project, None, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resources.fetchBy(IriSegment(iri), project, None, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Resource]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resources.fetch(IriSegment(iri), project, Some(schema)))
      case ResourceRef.Revision(_, iri, rev) =>
        resourceToValue(resources.fetchAt(IriSegment(iri), project, Some(schema), rev))
      case ResourceRef.Tag(_, iri, tag)      =>
        resourceToValue(resources.fetchBy(IriSegment(iri), project, Some(schema), tag))
    }

  private def resourceToValue(
      resourceIO: IO[ResourceRejection, DataResource]
  ): UIO[Option[ReferenceExchangeValue[Resource]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[Resource](
            toResource = res,
            toSource = res.value.source,
            toCompacted = res.toCompactedJsonLd,
            toExpanded = res.toExpandedJsonLd,
            toNTriples = res.toNTriples,
            toDot = res.toDot
          )
        )
      }
      .onErrorHandle(_ => None)
  }
}
