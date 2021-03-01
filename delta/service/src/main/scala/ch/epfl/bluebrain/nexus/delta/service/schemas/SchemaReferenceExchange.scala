package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, SchemaResource, Schemas}
import monix.bio.{IO, UIO}

/**
  * Storage specific [[ReferenceExchange]] implementation.
  *
  * @param schemas the schemas module
  */
class SchemaReferenceExchange(schemas: Schemas)(implicit baseUri: BaseUri, resolution: RemoteContextResolution)
    extends ReferenceExchange {

  override type A = Schema

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Schema]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(schemas.fetch(IriSegment(iri), project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(schemas.fetchAt(IriSegment(iri), project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(schemas.fetchBy(IriSegment(iri), project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Schema]]] =
    schema.original match {
      case Vocabulary.schemas.shacl => apply(project, reference)
      case _                        => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[SchemaRejection, SchemaResource]
  ): UIO[Option[ReferenceExchangeValue[Schema]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[Schema](
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
