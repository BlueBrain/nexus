package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Resolver, ResolverEvent, ResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{ReferenceExchange, ResolverResource, Resolvers}
import monix.bio.{IO, UIO}

/**
  * Resolver specific [[ReferenceExchange]] implementation.
  *
  * @param resolvers the resolvers module
  */
class ResolverReferenceExchange(resolvers: Resolvers)(implicit baseUri: BaseUri) extends ReferenceExchange {

  override type E = ResolverEvent
  override type A = Resolver
  override type M = Unit

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Resolver, Unit]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(resolvers.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(resolvers.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(resolvers.fetchBy(iri, project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Resolver, Unit]]] =
    schema.original match {
      case Vocabulary.schemas.resolvers => apply(project, reference)
      case _                            => UIO.pure(None)
    }

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: ResolverEvent => Some((value.project, value.id))
      case _                    => None
    }

  private def resourceToValue(
      resourceIO: IO[ResolverRejection, ResolverResource]
  ): UIO[Option[ReferenceExchangeValue[Resolver, Unit]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.source)(_ => ())) }
      .onErrorHandle(_ => None)
}
