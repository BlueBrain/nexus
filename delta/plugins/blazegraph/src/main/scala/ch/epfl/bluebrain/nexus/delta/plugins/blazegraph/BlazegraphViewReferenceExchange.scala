package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphView, BlazegraphViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * BlazegraphView specific [[ReferenceExchange]] implementation.
  *
  * @param views the blazegraph module
  */
class BlazegraphViewReferenceExchange(views: BlazegraphViews)(implicit
    baseUri: BaseUri,
    resolution: RemoteContextResolution
) extends ReferenceExchange {

  override type A = BlazegraphView

  override def apply(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(views.fetch(IriSegment(iri), project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(views.fetchAt(IriSegment(iri), project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(views.fetchBy(IriSegment(iri), project, tag))
    }

  private val schemaIri = model.schema.original

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView]]] =
    schema.original match {
      case `schemaIri` => apply(project, reference)
      case _           => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[BlazegraphViewRejection, ViewResource]
  ): UIO[Option[ReferenceExchangeValue[BlazegraphView]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[BlazegraphView](
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
