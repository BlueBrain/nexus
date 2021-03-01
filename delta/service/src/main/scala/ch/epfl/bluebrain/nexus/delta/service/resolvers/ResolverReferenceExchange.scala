package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
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
class ResolverReferenceExchange(resolvers: Resolvers)(implicit baseUri: BaseUri, resolution: RemoteContextResolution)
    extends ReferenceExchange {

  override type A = Resolver

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Resolver]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resolvers.fetch(IriSegment(iri), project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resolvers.fetchAt(IriSegment(iri), project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resolvers.fetchBy(IriSegment(iri), project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Resolver]]] =
    schema.original match {
      case Vocabulary.schemas.resolvers => apply(project, reference)
      case _                            => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[ResolverRejection, ResolverResource]
  ): UIO[Option[ReferenceExchangeValue[Resolver]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[Resolver](
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
