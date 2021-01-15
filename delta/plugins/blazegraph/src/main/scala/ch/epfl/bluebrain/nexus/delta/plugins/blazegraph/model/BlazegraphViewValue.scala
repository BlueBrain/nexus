package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, Configuration => JsonLdConfiguration}
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of Blazegraph view values.
  */
sealed trait BlazegraphViewValue extends Product with Serializable {

  /**
    * @return the view type
    */
  def tpe: BlazegraphViewType

  def toJson(iri: Iri): Json = this.asJsonObject.add(keywords.id, iri.asJson).asJson.dropNullValues
}

@nowarn("cat=unused")
object BlazegraphViewValue {

  /**
    * The configuration of the Blazegraph view that indexes resources as triples.
    *
    * @param resourceSchemas    the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes      the set of resource types considered for indexing; empty implies all
    * @param resourceTag        an optional tag to consider for indexing; when set, all resources that are tagged with
    *                           the value of the field are indexed with the corresponding revision
    * @param includeMetadata    whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated  whether to consider deprecated resources for indexing
    * @param permission         the permission required for querying this view
    */
  final case class IndexingBlazegraphViewValue(
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission
  ) extends BlazegraphViewValue {
    override val tpe: BlazegraphViewType = BlazegraphViewType.IndexingBlazegraphView
  }

  /**
    * The configuration of the Blazegraph view that delegates queries to multiple namespaces.
    *
    * @param views the collection of views where queries will be delegated (if necessary permissions are met)
    */
  final case class AggregateBlazegraphViewValue(views: NonEmptySet[ViewRef]) extends BlazegraphViewValue {
    override val tpe: BlazegraphViewType = BlazegraphViewType.AggregateBlazegraphView
  }

  implicit val viewRefEncoder: Encoder.AsObject[ViewRef] = {
    implicit val config: Configuration = Configuration.default
    deriveConfiguredEncoder[ViewRef]
  }
  implicit private[model] val blazegraphViewValueEncoder: Encoder.AsObject[BlazegraphViewValue] = {
    implicit val config: Configuration                   = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val tpeEncoder: Encoder[BlazegraphViewType] = deriveConfiguredEncoder[BlazegraphViewType]

    Encoder.encodeJsonObject.contramapObject { view =>
      deriveConfiguredEncoder[BlazegraphViewValue].encodeObject(view).add(keywords.tpe, view.tpe.tpe.asJson)
    }
  }

  implicit val blazegraphViewValueJsonLdDecoder: JsonLdDecoder[BlazegraphViewValue] = {

    implicit val config: JsonLdConfiguration              = JsonLdConfiguration.default
    implicit val viewRefEncoder: JsonLdDecoder[ViewRef]   = deriveConfigJsonLdDecoder[ViewRef]
    implicit val tagLabelEncoder: JsonLdDecoder[TagLabel] = _.get[String].map(TagLabel.unsafe)

    deriveConfigJsonLdDecoder[BlazegraphViewValue]
  }

}
