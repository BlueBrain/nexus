package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.ViewLens
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of BlazegraphView types
  */
sealed trait BlazegraphView extends Product with Serializable {

  /**
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return the tag -> rev mapping
    */
  def tags: Map[TagLabel, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: Json
}

object BlazegraphView {

  /**
    * A BlazegraphView that controls the projection of resource events to a Blazegraph namespace.
    *
    * @param id                 the view id
    * @param project            a reference to the parent project
    * @param uuid               the unique view identifier
    * @param resourceSchemas    the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes      the set of resource types considered for indexing; empty implies all
    * @param resourceTag        an optional tag to consider for indexing; when set, all resources that are tagged with
    *                           the value of the field are indexed with the corresponding revision
    * @param includeMetadata    whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated  whether to consider deprecated resources for indexing
    * @param permission         the permission required for querying this view
    * @param tags               the collection of tags for this resource
    * @param source             the original json value provided by the caller
    */
  final case class IndexingBlazegraphView(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission,
      tags: Map[TagLabel, Long],
      source: Json
  ) extends BlazegraphView

  /**
    * A Blazegraph view that delegates queries to multiple namespaces.
    *
    * @param id       the view id
    * @param project  a reference to the parent project
    * @param views    the collection of views where queries will be delegated (if necessary permissions are met)
    * @param tags     the collection of tags for this resource
    * @param source   the original json value provided by the caller
    */
  final case class AggregateBlazegraphView(
      id: Iri,
      project: ProjectRef,
      views: NonEmptySet[ViewRef],
      tags: Map[TagLabel, Long],
      source: Json
  ) extends BlazegraphView

  implicit def indexingViewLens(implicit config: ExternalIndexingConfig): ViewLens[IndexingViewResource] =
    new ViewLens[IndexingViewResource] {

      override def rev(view: IndexingViewResource): Long  = view.rev
      override def uuid(view: IndexingViewResource): UUID = view.value.uuid

      override def projectionId(view: IndexingViewResource): ProjectionId.ViewProjectionId =
        ViewProjectionId(s"blazegraph-${view.value.uuid}_${view.rev}")

      override def index(view: IndexingViewResource): String =
        s"${config.prefix}_${uuid(view)}_${rev(view)}"
    }

  @nowarn("cat=unused")
  implicit private val blazegraphViewsEncoder: Encoder.AsObject[BlazegraphView] = {
    implicit val config: Configuration                     = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[TagLabel, Long]] = Encoder.instance(_ => Json.Null)
    Encoder.encodeJsonObject.contramapObject { v =>
      deriveConfiguredEncoder[BlazegraphView].encodeObject(v).remove("tags").remove("source").remove("id")
    }
  }

  implicit val blazegraphViewsJsonLdEncoder: JsonLdEncoder[BlazegraphView] =
    JsonLdEncoder.computeFromCirce(_.id, ContextValue(contexts.blazegraph))
}
