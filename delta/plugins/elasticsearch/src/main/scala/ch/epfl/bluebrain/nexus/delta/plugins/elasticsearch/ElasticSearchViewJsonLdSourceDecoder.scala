package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewJsonLdSourceDecoder.{toValue, ElasticSearchViewFields}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, permissions, ElasticSearchViewRejection, ElasticSearchViewType, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

import scala.annotation.nowarn

/**
  * Decoder for [[ElasticSearchViewValue]] which maps some fields to string, before decoding to get around lack of
  * support for @json in json ld library.
  */
//TODO remove when support for @json is added in json-ld library
class ElasticSearchViewJsonLdSourceDecoder private (
    decoder: JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields]
) {

  def apply(project: Project, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, (Iri, ElasticSearchViewValue)] =
    decoder(project, mapJsonToString(source)).map { case (iri, fields) =>
      iri -> toValue(fields)
    }

  def apply(project: Project, iri: Iri, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, ElasticSearchViewValue] =
    decoder(
      project,
      iri,
      mapJsonToString(source)
    ).map(toValue)

  private def mapJsonToString(json: Json): Json = json
    .mapAllKeys("mapping", _.noSpaces.asJson)
    .mapAllKeys("settings", _.noSpaces.asJson)
}

object ElasticSearchViewJsonLdSourceDecoder {

  sealed private trait ElasticSearchViewFields extends Product with Serializable {
    def tpe: ElasticSearchViewType
  }

  private object ElasticSearchViewFields {

    final case class IndexingElasticSearchViewFields(
        resourceSchemas: Set[Iri] = Set.empty,
        resourceTypes: Set[Iri] = Set.empty,
        resourceTag: Option[TagLabel] = None,
        sourceAsText: Boolean = false,
        includeMetadata: Boolean = false,
        includeDeprecated: Boolean = false,
        mapping: JsonObject,
        settings: Option[JsonObject] = None,
        permission: Permission = permissions.query
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
    }

    final case class AggregateElasticSearchViewFields(
        views: NonEmptySet[ViewRef]
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
    }

    @nowarn("cat=unused")
    implicit final val elasticSearchViewFieldsJsonLdDecoder: JsonLdDecoder[ElasticSearchViewFields] = {

      val ctx = Configuration.default.context
        .addAliasIdType("IndexingElasticSearchViewFields", ElasticSearchViewType.ElasticSearch.tpe)
        .addAliasIdType("AggregateElasticSearchViewFields", ElasticSearchViewType.AggregateElasticSearch.tpe)

      implicit val cfg: Configuration = Configuration.default.copy(context = ctx)

      deriveConfigJsonLdDecoder[ElasticSearchViewFields]
    }

  }

  import ElasticSearchViewFields._
  private def toValue(fields: ElasticSearchViewFields): ElasticSearchViewValue = fields match {
    case i: IndexingElasticSearchViewFields  =>
      IndexingElasticSearchViewValue(
        resourceSchemas = i.resourceSchemas,
        resourceTypes = i.resourceTypes,
        resourceTag = i.resourceTag,
        sourceAsText = i.sourceAsText,
        includeMetadata = i.includeMetadata,
        includeDeprecated = i.includeDeprecated,
        mapping = Some(i.mapping),
        settings = i.settings,
        permission = i.permission
      )
    case a: AggregateElasticSearchViewFields =>
      AggregateElasticSearchViewValue(a.views)
  }

  def apply(uuidF: UUIDF, contextResolution: ResolverContextResolution)(implicit api: JsonLdApi) =
    new ElasticSearchViewJsonLdSourceDecoder(
      new JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields](
        contexts.elasticsearch,
        contextResolution,
        uuidF
      )
    )
}
