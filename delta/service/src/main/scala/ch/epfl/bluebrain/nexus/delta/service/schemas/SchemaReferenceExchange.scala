package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, SchemaResource, Schemas}
import monix.bio.{IO, UIO}

/**
  * Schema specific [[ReferenceExchange]] implementation.
  *
  * @param schemas the schemas module
  */
class SchemaReferenceExchange(schemas: Schemas) extends ReferenceExchange {

  override type E = SchemaEvent
  override type A = Schema
  override type M = Unit

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Schema, Unit]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(schemas.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(schemas.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(schemas.fetchBy(iri, project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Schema, Unit]]] =
    schema.original match {
      case Vocabulary.schemas.shacl => apply(project, reference)
      case _                        => UIO.pure(None)
    }

  override def apply(event: Event): Option[(ProjectRef, IriOrBNode.Iri)] =
    event match {
      case value: SchemaEvent => Some((value.project, value.id))
      case _                  => None
    }

  private def resourceToValue(
      resourceIO: IO[SchemaRejection, SchemaResource]
  ): UIO[Option[ReferenceExchangeValue[Schema, Unit]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.source)(_ => ())) }
      .onErrorHandle(_ => None)
}
