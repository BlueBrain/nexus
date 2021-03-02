package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchView, ElasticSearchViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * ElasticSearchView specific [[ReferenceExchange]] implementation.
  *
  * @param views the elasticsearch module
  */
class ElasticSearchViewReferenceExchange(views: ElasticSearchViews)(implicit
    baseUri: BaseUri,
    resolution: RemoteContextResolution
) extends ReferenceExchange {

  override type A = ElasticSearchView

  override def apply(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView]]] =
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
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView]]] =
    schema.original match {
      case `schemaIri` => apply(project, reference)
      case _           => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[ElasticSearchViewRejection, ViewResource]
  ): UIO[Option[ReferenceExchangeValue[ElasticSearchView]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[ElasticSearchView](
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
