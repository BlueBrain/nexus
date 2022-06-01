package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.{ViewIndex, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import java.util.UUID
import scala.annotation.nowarn

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
    *   a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return
    *   the tag -> rev mapping
    */
  def tags: Map[UserTag, Long]

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
    * @param tags
    *   the collection of tags for this resource
    * @param source
    *   the original json value provided by the caller
    */
  final case class IndexingBlazegraphView(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission,
      tags: Map[UserTag, Long],
      source: Json
  ) extends BlazegraphView {
    override def metadata: Metadata = Metadata(Some(uuid))

    override def tpe: BlazegraphViewType = BlazegraphViewType.IndexingBlazegraphView
  }

  object IndexingBlazegraphView {

    /**
      * Create the view index from the [[IndexingBlazegraphView]]
      */
    def resourceToViewIndex(
        res: IndexingViewResource,
        config: BlazegraphViewsConfig
    ): ViewIndex[IndexingBlazegraphView] = ViewIndex(
      res.value.project,
      res.id,
      BlazegraphViews.projectionId(res),
      BlazegraphViews.namespace(res, config.indexing),
      res.rev,
      res.deprecated,
      res.value.resourceTag,
      res.value
    )
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
    * @param tags
    *   the collection of tags for this resource
    * @param source
    *   the original json value provided by the caller
    */
  final case class AggregateBlazegraphView(
      id: Iri,
      project: ProjectRef,
      views: NonEmptySet[ViewRef],
      tags: Map[UserTag, Long],
      source: Json
  ) extends BlazegraphView {
    override def metadata: Metadata      = Metadata(None)
    override def tpe: BlazegraphViewType = BlazegraphViewType.AggregateBlazegraphView

  }

  /**
    * BlazegraphView metadata.
    *
    * @param uuid
    *   the optionally available unique view identifier
    */
  final case class Metadata(uuid: Option[UUID])

  @nowarn("cat=unused")
  implicit private val blazegraphViewsEncoder: Encoder.AsObject[BlazegraphView] = {
    implicit val config: Configuration                    = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[UserTag, Long]] = Encoder.instance(_ => Json.Null)
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
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject.empty.addIfExists("_uuid", meta.uuid))

  implicit val blazegraphMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.blazegraphMetadata))
}
