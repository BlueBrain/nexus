package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, SchemaResource, Schemas}
import monix.bio.{IO, UIO}

/**
  * Schema specific [[ReferenceExchange]] implementation.
  *
  * @param schemas the schemas module
  */
class SchemaReferenceExchange(schemas: Schemas) extends ReferenceExchange {

  override type A = Schema

  override def toResource(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Schema]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(schemas.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(schemas.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(schemas.fetchBy(iri, project, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Schema]]] =
    schema.original match {
      case Vocabulary.schemas.shacl => toResource(project, reference)
      case _                        => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[SchemaRejection, SchemaResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[Schema]]] =
    resourceIO
      .map(res => Some(ReferenceExchangeValue(res, res.value.source, enc)))
      .onErrorHandle(_ => None)
}
