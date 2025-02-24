package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import java.util.UUID

/**
  * Enumeration of BlazegraphView types
  */
sealed trait BlazegraphView extends Product with Serializable {

  /**
    * @return
    *   the view id
    */
  def id: Iri

  /**
    * @return
    *   the name of the view
    */
  def name: Option[String]

  /**
    * @return
    *   the description of the view
    */
  def description: Option[String]

  /**
    * @return
    *   a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return
    *   the original json document provided at creation or update
    */
  def source: Json

  /**
    * @return
    *   [[BlazegraphView]] metadata
    */
  def metadata: Metadata

  /**
    * @return
    *   the Blazegraph view type
    */
  def tpe: BlazegraphViewType
}

object BlazegraphView {

  /**
    * A BlazegraphView that controls the projection of resource events to a Blazegraph namespace.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param uuid
    *   the unique view identifier
    * @param resourceSchemas
    *   the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes
    *   the set of resource types considered for indexing; empty implies all
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param includeMetadata
    *   whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated
    *   whether to consider deprecated resources for indexing
    * @param permission
    *   the permission required for querying this view
    * @param source
    *   the original json value provided by the caller
    */
  final case class IndexingBlazegraphView(
      id: Iri,
      name: Option[String],
      description: Option[String],
      project: ProjectRef,
      uuid: UUID,
      resourceSchemas: IriFilter,
      resourceTypes: IriFilter,
      resourceTag: Option[UserTag],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission,
      source: Json,
      indexingRev: Int
  ) extends BlazegraphView {
    override def metadata: Metadata = Metadata(Some(uuid), Some(indexingRev))

    override def tpe: BlazegraphViewType = BlazegraphViewType.IndexingBlazegraphView
  }

  /**
    * A Blazegraph view that delegates queries to multiple namespaces.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param views
    *   the collection of views where queries will be delegated (if necessary permissions are met)
    * @param source
    *   the original json value provided by the caller
    */
  final case class AggregateBlazegraphView(
      id: Iri,
      name: Option[String],
      description: Option[String],
      project: ProjectRef,
      views: NonEmptySet[ViewRef],
      source: Json
  ) extends BlazegraphView {
    override def metadata: Metadata      = Metadata(None, None)
    override def tpe: BlazegraphViewType = BlazegraphViewType.AggregateBlazegraphView

  }

  /**
    * BlazegraphView metadata.
    *
    * @param uuid
    *   the optionally available unique view identifier
    */
  final case class Metadata(uuid: Option[UUID], indexingRev: Option[Int])

  implicit private val blazegraphViewsEncoder: Encoder.AsObject[BlazegraphView] = {
    implicit val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)
    Encoder.encodeJsonObject.contramapObject { v =>
      deriveConfiguredEncoder[BlazegraphView]
        .encodeObject(v)
        .add(keywords.tpe, v.tpe.types.asJson)
        .remove("tags")
        .remove("project")
        .remove("source")
        .remove("id")
        .addContext(v.source.topContextValueOrEmpty.excludeRemoteContexts.contextObj)
    }
  }

  implicit val blazegraphViewsJsonLdEncoder: JsonLdEncoder[BlazegraphView] =
    JsonLdEncoder.computeFromCirce(_.id, ContextValue(contexts.blazegraph))

  implicit private val blazegraphMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta =>
      JsonObject.empty
        .addIfExists("_uuid", meta.uuid)
        .addIfExists("_indexingRev", meta.indexingRev)
    )

  implicit val blazegraphMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.blazegraphMetadata))
}
