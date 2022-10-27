package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.data.{NonEmptyChain, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DefaultLabelPredicates, DiscardMetadata, FilterDeprecated}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, PipeRef}
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import scala.annotation.nowarn

/**
  * Enumeration of ElasticSearch values.
  */
sealed trait ElasticSearchViewValue extends Product with Serializable {

  /**
    * @return
    *   the view type
    */
  def tpe: ElasticSearchViewType

  def toJson(iri: Iri): Json = {
    import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.Source._
    this.asJsonObject.add(keywords.id, iri.asJson).asJson.deepDropNullValues
  }

  def asIndexingValue: Option[IndexingElasticSearchViewValue] = this match {
    case v: IndexingElasticSearchViewValue => Some(v)
    case _                                 => None
  }

}

object ElasticSearchViewValue {

  /**
    * The configuration of the ElasticSearch view that indexes resources as documents.
    *
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param pipeline
    *   the list of operations to apply on a resource before indexing
    * @param mapping
    *   the elasticsearch mapping to be used in order to create the index
    * @param settings
    *   the elasticsearch optional settings to be used in order to create the index
    * @param context
    *   an optional context to apply when compacting during the creation of the document to index
    * @param permission
    *   the permission required for querying this view
    */
  final case class IndexingElasticSearchViewValue(
      resourceTag: Option[UserTag],
      pipeline: List[PipeStep],
      mapping: Option[JsonObject],
      settings: Option[JsonObject],
      context: Option[ContextObject],
      permission: Permission
  ) extends ElasticSearchViewValue {
    override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch

    def pipeChain: Option[PipeChain] =
      NonEmptyChain.fromSeq(pipeline).map { steps =>
        val pipes = steps.map { step =>
          (PipeRef(step.name), step.config.getOrElse(ExpandedJsonLd.empty))
        }
        PipeChain(pipes)
      }
  }

  object IndexingElasticSearchViewValue {

    /**
      * Default pipeline to apply if none is present in the payload
      */
    val defaultPipeline: List[PipeStep] = List(
      PipeStep(FilterDeprecated.label, None, None),
      PipeStep(DiscardMetadata.label, None, None),
      PipeStep(DefaultLabelPredicates.label, None, None)
    )
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

  object Source {
    @nowarn("cat=unused")
    implicit final val elasticSearchViewValueEncoder: Encoder.AsObject[ElasticSearchViewValue] = {
      import io.circe.generic.extras.Configuration
      import io.circe.generic.extras.semiauto._
      implicit val config: Configuration = Configuration(
        transformMemberNames = identity,
        transformConstructorNames = {
          case "IndexingElasticSearchViewValue"  => ElasticSearchViewType.ElasticSearch.toString
          case "AggregateElasticSearchViewValue" => ElasticSearchViewType.AggregateElasticSearch.toString
          case other                             => other
        },
        useDefaults = false,
        discriminator = Some(keywords.tpe),
        strictDecoding = false
      )
      deriveConfiguredEncoder[ElasticSearchViewValue]
    }
  }

}
