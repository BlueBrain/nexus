package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.implicits._
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.parser.parse
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of ElasticSearch values.
  */
sealed trait ElasticSearchViewValue extends Product with Serializable {

  /**
    * @return the view type
    */
  def tpe: ElasticSearchViewType
}

object ElasticSearchViewValue {

  /**
    * The configuration of the ElasticSearch view that indexes resources as documents.
    *
    * @param resourceSchemas   the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes     the set of resource types considered for indexing; empty implies all
    * @param resourceTag       an optional tag to consider for indexing; when set, all resources that are tagged with
    *                          the value of the field are indexed with the corresponding revision
    * @param sourceAsText      whether to include the source of the resource as a text field in the document
    * @param includeMetadata   whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated whether to consider deprecated resources for indexing
    * @param mapping           the elasticsearch mapping to be used
    * @param permission        the permission required for querying this view
    */
  final case class IndexingElasticSearchViewValue(
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[TagLabel] = None,
      sourceAsText: Boolean = true,
      includeMetadata: Boolean = true,
      includeDeprecated: Boolean = true,
      mapping: Json,
      permission: Permission = Permission.unsafe("views/query")
  ) extends ElasticSearchViewValue {
    override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
  }

  /**
    * The configuration of the ElasticSearch view that delegates queries to multiple indices.
    *
    * @param views the collection of views where queries will be delegated (if necessary permissions are met)
    */
  final case class AggregateElasticSearchViewValue(
      views: NonEmptySet[ViewRef]
  ) extends ElasticSearchViewValue {
    override val tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
  }

  @nowarn("cat=unused")
  implicit final val elasticSearchViewValueEncoder: Encoder.AsObject[ElasticSearchViewValue] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = identity,
      transformConstructorNames = {
        case "IndexingElasticSearchViewValue"  => "ElasticSearchView"
        case "AggregateElasticSearchViewValue" => "AggregateElasticSearchView"
        case other                             => other
      },
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[ElasticSearchViewValue]
  }

  @nowarn("cat=unused")
  implicit final val elasticSearchViewValueJsonLdDecoder: JsonLdDecoder[ElasticSearchViewValue] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._

    val ctx = Configuration.default.context
      .addAliasIdType("IndexingElasticSearchViewValue", ElasticSearchViewType.ElasticSearch.iri)
      .addAliasIdType("AggregateElasticSearchViewValue", ElasticSearchViewType.AggregateElasticSearch.iri)

    implicit val cfg: Configuration = Configuration.default.copy(context = ctx)

    // assumes the field is encoded as a string
    // TODO: remove when `@type: json` is supported by the json-ld lib
    implicit val jsonJsonLdDecoder: JsonLdDecoder[Json] = (cursor: ExpandedJsonLdCursor) =>
      cursor
        .get[String]
        .flatMap(s => parse(s).leftMap(_ => ParsingFailure("Json", s, cursor.history)))

    deriveConfigJsonLdDecoder[ElasticSearchViewValue]
  }

}
