package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Resolver, ResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, ResolverResource, Resolvers}
import monix.bio.{IO, UIO}

/**
  * Resolver specific [[ReferenceExchange]] implementation.
  *
  * @param resolvers the resolvers module
  */
class ResolverReferenceExchange(resolvers: Resolvers)(implicit baseUri: BaseUri) extends ReferenceExchange {

  override type A = Resolver

  override def toResource(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resolvers.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resolvers.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resolvers.fetchBy(iri, project, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    schema.original match {
      case schemas.resolvers => toResource(project, reference)
      case _                 => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[ResolverRejection, ResolverResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[A]]] =
    resourceIO
      .map(res => Some(ReferenceExchangeValue(res, res.value.source, enc)))
      .onErrorHandle(_ => None)
}
