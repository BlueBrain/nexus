package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * ElasticSearchView specific [[ReferenceExchange]] implementation.
  *
  * @param views the elasticsearch module
  */
class ElasticSearchViewReferenceExchange(views: ElasticSearchViews) extends ReferenceExchange {

  override type E = ElasticSearchViewEvent
  override type A = ElasticSearchView
  override type M = Metadata

  override def apply(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView, Metadata]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(views.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(views.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(views.fetchBy(iri, project, tag))
    }

  private val schemaIri = model.schema.original

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView, Metadata]]] =
    schema.original match {
      case `schemaIri` => apply(project, reference)
      case _           => UIO.pure(None)
    }

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: ElasticSearchViewEvent => Some((value.project, value.id))
      case _                             => None
    }

  private def resourceToValue(
      resourceIO: IO[ElasticSearchViewRejection, ViewResource]
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView, Metadata]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.source)(_.metadata)) }
      .onErrorHandle(_ => None)
}
