package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, Schemas}
import monix.bio.UIO

/**
  * [[ReferenceExchange]] implementation for schemas.
  *
  * @param schemas the schemas module
  */
class SchemaReferenceExchange(schemas: Schemas)(implicit
    baseUri: BaseUri,
    options: JsonLdOptions,
    api: JsonLdApi,
    resolution: RemoteContextResolution
) extends ReferenceExchange {

  override type A = Schema

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]] = {
    val resourceIO = reference match {
      case ResourceRef.Latest(iri)           => schemas.fetch(IriSegment(iri), project)
      case ResourceRef.Revision(_, iri, rev) => schemas.fetchAt(IriSegment(iri), project, rev)
      case ResourceRef.Tag(_, iri, tag)      => schemas.fetchBy(IriSegment(iri), project, tag)
    }
    resourceIO
      .map { resource =>
        Some(
          new ReferenceExchangeValue(
            toResource = resource,
            toCompacted = resource.toCompactedJsonLd,
            toExpanded = resource.toExpandedJsonLd,
            toSource = resource.value.source
          )
        )
      }
      .onErrorHandle(_ => None)
  }
}
