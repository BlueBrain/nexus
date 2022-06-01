package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of Blazegraph view values.
  */
sealed trait BlazegraphViewValue extends Product with Serializable {

  /**
    * @return
    *   the view type
    */
  def tpe: BlazegraphViewType

  def toJson(iri: Iri): Json = this.asJsonObject.add(keywords.id, iri.asJson).asJson.dropNullValues
}

@nowarn("cat=unused")
object BlazegraphViewValue {

  /**
    * The configuration of the Blazegraph view that indexes resources as triples.
    *
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
    */
  final case class IndexingBlazegraphViewValue(
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeMetadata: Boolean = false,
      includeDeprecated: Boolean = false,
      permission: Permission = permissions.query
  ) extends BlazegraphViewValue {
    override val tpe: BlazegraphViewType = BlazegraphViewType.IndexingBlazegraphView
  }

  /**
    * The configuration of the Blazegraph view that delegates queries to multiple namespaces.
    *
    * @param views
    *   the collection of views where queries will be delegated (if necessary permissions are met)
    */
  final case class AggregateBlazegraphViewValue(views: NonEmptySet[ViewRef]) extends BlazegraphViewValue {
    override val tpe: BlazegraphViewType = BlazegraphViewType.AggregateBlazegraphView
  }

  implicit private[model] val blazegraphViewValueEncoder: Encoder.AsObject[BlazegraphViewValue] = {
    import io.circe.generic.extras.Configuration

    implicit val config: Configuration = Configuration(
      transformMemberNames = identity,
      transformConstructorNames = {
        case "IndexingBlazegraphViewValue"  => BlazegraphViewType.IndexingBlazegraphView.toString
        case "AggregateBlazegraphViewValue" => BlazegraphViewType.AggregateBlazegraphView.toString
        case other                          => other
      },
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[BlazegraphViewValue]
  }

  implicit val blazegraphViewValueJsonLdDecoder: JsonLdDecoder[BlazegraphViewValue] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration

    val ctx = Configuration.default.context
      .addAliasIdType("IndexingBlazegraphViewValue", BlazegraphViewType.IndexingBlazegraphView.tpe)
      .addAliasIdType("AggregateBlazegraphViewValue", BlazegraphViewType.AggregateBlazegraphView.tpe)

    implicit val cfg: Configuration = Configuration.default.copy(context = ctx)

    deriveConfigJsonLdDecoder[BlazegraphViewValue]
  }

}
