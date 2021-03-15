package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * BlazegraphView specific [[ReferenceExchange]] implementation.
  *
  * @param views the blazegraph module
  */
class BlazegraphViewReferenceExchange(views: BlazegraphViews) extends ReferenceExchange {

  override type E = BlazegraphViewEvent
  override type A = BlazegraphView
  override type M = Metadata

  override def apply(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView, Metadata]]] =
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
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView, Metadata]]] =
    schema.original match {
      case `schemaIri` => apply(project, reference)
      case _           => UIO.pure(None)
    }

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: BlazegraphViewEvent => Some((value.project, value.id))
      case _                          => None
    }

  private def resourceToValue(
      resourceIO: IO[BlazegraphViewRejection, ViewResource]
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView, Metadata]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.source)(_.metadata)) }
      .onErrorHandle(_ => None)
}
