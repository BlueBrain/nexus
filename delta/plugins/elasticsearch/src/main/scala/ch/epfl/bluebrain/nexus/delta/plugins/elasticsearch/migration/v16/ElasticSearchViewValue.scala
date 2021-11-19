package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration.v16

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{Decoder, JsonObject}

import scala.annotation.nowarn

/**
  * Enumeration of ElasticSearch values.
  */
sealed private[migration] trait ElasticSearchViewValue extends Product with Serializable {

  /**
    * @return
    *   the view type
    */
  def tpe: ElasticSearchViewType
}

@nowarn("cat=unused")
private[migration] object ElasticSearchViewValue {

  /**
    * The configuration of the ElasticSearch view that indexes resources as documents.
    *
    * @param resourceSchemas
    *   the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes
    *   the set of resource types considered for indexing; empty implies all
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param sourceAsText
    *   whether to include the source of the resource as a text field in the document
    * @param includeMetadata
    *   whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated
    *   whether to consider deprecated resources for indexing
    * @param mapping
    *   the elasticsearch mapping to be used in order to create the index
    * @param settings
    *   the elasticsearch optional settings to be used in order to create the index
    * @param permission
    *   the permission required for querying this view
    */
  final case class IndexingElasticSearchViewValue(
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      sourceAsText: Boolean,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      mapping: Option[JsonObject],
      settings: Option[JsonObject],
      permission: Permission
  ) extends ElasticSearchViewValue {
    override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
  }

  /**
    * The configuration of the ElasticSearch view that delegates queries to multiple indices.
    *
    * @param views
    *   the collection of views where queries will be delegated (if necessary permissions are met)
    */
  final case class AggregateElasticSearchViewValue(
      views: NonEmptySet[ViewRef]
  ) extends ElasticSearchViewValue {
    override val tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
  }

  implicit final private val configuration: Configuration = Configuration.default.withDiscriminator(keywords.tpe)

  implicit val oldElasticSearchViewValueDecoder: Decoder[ElasticSearchViewValue] =
    deriveConfiguredDecoder[ElasticSearchViewValue]

}
