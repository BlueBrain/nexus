package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.{IO, UIO}

/**
  * CompositeView specific [[ReferenceExchange]] implementation.
  *
  * @param views the composite view module
  */
class CompositeViewReferenceExchange(views: CompositeViews)(implicit base: BaseUri) extends ReferenceExchange {

  override type A = CompositeView
  private val schemaIri = schema.original

  override def toResource(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(views.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(views.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(views.fetchBy(iri, project, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    schema.original match {
      case `schemaIri` => toResource(project, reference)
      case _           => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[CompositeViewRejection, ViewResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[A]]] =
    resourceIO
      .map(res => Some(ReferenceExchangeValue(res, res.value.source, enc)))
      .onErrorHandle(_ => None)
}
