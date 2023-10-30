package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIOOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewJsonLdSourceDecoder.{toValue, ElasticSearchViewFields}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, permissions, ElasticSearchViewRejection, ElasticSearchViewType, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.{PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import io.circe.syntax._
import io.circe.{Json, JsonObject}

import scala.annotation.nowarn

/**
  * Decoder for [[ElasticSearchViewValue]] which maps some fields to string, before decoding to get around lack of
  * support for @json in json ld library.
  */
//TODO remove when support for @json is added in json-ld library
class ElasticSearchViewJsonLdSourceDecoder private (
    decoder: JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields]
) {

  def apply(ref: ProjectRef, context: ProjectContext, source: Json)(implicit
      caller: Caller
  ): IO[(Iri, ElasticSearchViewValue)] =
    decoder(ref, context, mapJsonToString(source)).toCatsIO.map { case (iri, fields) =>
      iri -> toValue(fields)
    }

  def apply(ref: ProjectRef, context: ProjectContext, iri: Iri, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewValue] =
    decoder(
      ref,
      context,
      iri,
      mapJsonToString(source)
    ).toCatsIO.map(toValue)

  private def mapJsonToString(json: Json): Json = json
    .mapAllKeys("mapping", _.noSpaces.asJson)
    .mapAllKeys("settings", _.noSpaces.asJson)
}

object ElasticSearchViewJsonLdSourceDecoder {

  sealed private trait ElasticSearchViewFields extends Product with Serializable {
    def name: Option[String]
    def description: Option[String]
    def tpe: ElasticSearchViewType
  }

  private object ElasticSearchViewFields {

    // Describe the legacy payload using deprecated fields to keep retro-compatibility
    final case class LegacyIndexingElasticSearchViewFields(
        name: Option[String] = None,
        description: Option[String] = None,
        resourceSchemas: Set[Iri] = Set.empty,
        resourceTypes: Set[Iri] = Set.empty,
        resourceTag: Option[UserTag] = None,
        sourceAsText: Boolean = false,
        includeMetadata: Boolean = false,
        includeDeprecated: Boolean = false,
        mapping: JsonObject,
        settings: Option[JsonObject] = None,
        permission: Permission = permissions.query
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
    }

    final case class IndexingElasticSearchViewFields(
        name: Option[String] = None,
        description: Option[String] = None,
        resourceTag: Option[UserTag] = None,
        pipeline: Option[List[PipeStep]] = None,
        mapping: JsonObject,
        settings: Option[JsonObject] = None,
        context: Option[ContextObject] = None,
        permission: Permission = permissions.query
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
    }

    final case class AggregateElasticSearchViewFields(
        name: Option[String] = None,
        description: Option[String] = None,
        views: NonEmptySet[ViewRef]
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
    }

    @nowarn("cat=unused")
    implicit final def elasticSearchViewFieldsJsonLdDecoder(implicit
        configuration: Configuration
    ): JsonLdDecoder[ElasticSearchViewFields] = {
      val legacyFieldsJsonLdDecoder    = deriveConfigJsonLdDecoder[LegacyIndexingElasticSearchViewFields]
      val indexingFieldsJsonLdDecoder  = deriveConfigJsonLdDecoder[IndexingElasticSearchViewFields]
      val aggregateFieldsJsonLdDecoder = deriveConfigJsonLdDecoder[AggregateElasticSearchViewFields]
      val pipeline                     = nxv + "pipeline"
      (cursor: ExpandedJsonLdCursor) =>
        cursor.getTypes.flatMap { types =>
          (
            types.contains(ElasticSearchViewType.ElasticSearch.tpe),
            types.contains(ElasticSearchViewType.AggregateElasticSearch.tpe)
          ) match {
            case (true, true)   =>
              Left(
                ParsingFailure(
                  s"The payload can't contain both '${ElasticSearchViewType.ElasticSearch}' and '${ElasticSearchViewType.AggregateElasticSearch}' types.'"
                )
              )
            case (true, false)  =>
              //TODO implement strict json-ld decoding
              if (cursor.downField(pipeline).succeeded)
                indexingFieldsJsonLdDecoder(cursor)
              else
                legacyFieldsJsonLdDecoder(cursor)
            case (false, true)  =>
              aggregateFieldsJsonLdDecoder(cursor)
            case (false, false) =>
              Left(
                ParsingFailure(
                  s"The payload has to contain '${ElasticSearchViewType.ElasticSearch}' or '${ElasticSearchViewType.AggregateElasticSearch}' types.'"
                )
              )
          }
        }
    }

  }

  import ElasticSearchViewFields._

  private def toValue(fields: ElasticSearchViewFields): ElasticSearchViewValue = fields match {
    case i: LegacyIndexingElasticSearchViewFields =>
      // Translate legacy fields into a pipeline
      val pipeline = List(
        i.resourceSchemas.nonEmpty -> PipeStep(FilterBySchema(i.resourceSchemas)),
        i.resourceTypes.nonEmpty   -> PipeStep(FilterByType(i.resourceTypes)),
        !i.includeDeprecated       -> PipeStep.noConfig(FilterDeprecated.ref),
        !i.includeMetadata         -> PipeStep.noConfig(DiscardMetadata.ref),
        true                       -> PipeStep.noConfig(DefaultLabelPredicates.ref),
        i.sourceAsText             -> PipeStep.noConfig(SourceAsText.ref)
      ).mapFilter { case (b, p) => Option.when(b)(p) }

      IndexingElasticSearchViewValue(
        name = i.name,
        description = i.description,
        resourceTag = i.resourceTag,
        pipeline,
        mapping = Some(i.mapping),
        settings = i.settings,
        context = None,
        permission = i.permission
      )
    case i: IndexingElasticSearchViewFields       =>
      IndexingElasticSearchViewValue(
        name = i.name,
        description = i.description,
        resourceTag = i.resourceTag,
        // If no pipeline defined, we use a default one to keep the historic behaviour
        pipeline = i.pipeline.getOrElse(IndexingElasticSearchViewValue.defaultPipeline),
        mapping = Some(i.mapping),
        settings = i.settings,
        context = i.context,
        permission = i.permission
      )
    case a: AggregateElasticSearchViewFields      =>
      AggregateElasticSearchViewValue(
        name = a.name,
        description = a.description,
        views = a.views
      )
  }

  def apply(uuidF: UUIDF, contextResolution: ResolverContextResolution)(implicit
      api: JsonLdApi
  ): IO[ElasticSearchViewJsonLdSourceDecoder] = {
    implicit val rcr: RemoteContextResolution = contextResolution.rcr

    ElasticSearchDecoderConfiguration.apply.map { implicit config =>
      new ElasticSearchViewJsonLdSourceDecoder(
        new JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields](
          contexts.elasticsearch,
          contextResolution,
          uuidF
        )
      )
    }
  }
}
