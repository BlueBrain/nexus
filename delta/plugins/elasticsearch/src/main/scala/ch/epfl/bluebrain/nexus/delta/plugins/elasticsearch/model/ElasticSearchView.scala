package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.{ViewIndex, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import monix.bio.IO

import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of ElasticSearchView types.
  */
sealed trait ElasticSearchView extends Product with Serializable {

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
    *   [[ElasticSearchView]] metadata
    */
  def metadata: Metadata

  /**
    * @return
    *   the ElasticSearch view type
    */
  def tpe: ElasticSearchViewType
}

object ElasticSearchView {

  /**
    * An ElasticSearch view that controls the projection of resource events to an ElasticSearch index.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param uuid
    *   the unique view identifier
    * @param pipeline
    *   the list of operations to apply on a resource before indexing it
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param mapping
    *   the elasticsearch mapping to be used in order to create the index
    * @param settings
    *   the elasticsearch optional settings to be used in order to create the index
    * @param permission
    *   the permission required for querying this view
    * @param tags
    *   the collection of tags for this resource
    * @param source
    *   the original json value provided by the caller
    */
  final case class IndexingElasticSearchView(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      resourceTag: Option[UserTag],
      pipeline: List[PipeDef],
      mapping: JsonObject,
      settings: JsonObject,
      context: Option[ContextObject],
      permission: Permission,
      tags: Map[UserTag, Long],
      source: Json
  ) extends ElasticSearchView {
    override def metadata: Metadata = Metadata(Some(uuid))

    override def tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
  }

  object IndexingElasticSearchView {

    /**
      * Create the view index from the [[IndexingElasticSearchView]]
      */
    def resourceToViewIndex(
        res: IndexingViewResource,
        config: ElasticSearchViewsConfig
    ): ViewIndex[IndexingElasticSearchView] =
      ViewIndex(
        res.value.project,
        res.id,
        ElasticSearchViews.projectionId(res),
        ElasticSearchViews.index(res, config.indexing),
        res.rev,
        res.deprecated,
        res.value.resourceTag,
        res.value
      )
  }

  /**
    * An ElasticSearch view that delegates queries to multiple indices.
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
  final case class AggregateElasticSearchView(
      id: Iri,
      project: ProjectRef,
      views: NonEmptySet[ViewRef],
      tags: Map[UserTag, Long],
      source: Json
  ) extends ElasticSearchView {
    override def metadata: Metadata         = Metadata(None)
    override def tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
  }

  /**
    * ElasticSearchView metadata.
    *
    * @param uuid
    *   the optionally available unique view identifier
    */
  final case class Metadata(uuid: Option[UUID])

  val context: ContextValue = ContextValue(contexts.elasticsearch)

  @nowarn("cat=unused")
  implicit val elasticSearchViewEncoder: Encoder.AsObject[ElasticSearchView] = {
    implicit val config: Configuration                    = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[UserTag, Long]] = Encoder.instance(_ => Json.Null)

    // To keep retro-compatibility, we compute legacy fields from the view pipeline
    def encodeLegacyFields(v: ElasticSearchView) =
      v match {
        case _: AggregateElasticSearchView => JsonObject.empty
        case i: IndexingElasticSearchView  =>
          // Default legacy values
          JsonObject(
            "resourceSchemas"   -> Json.arr(),
            "resourceTypes"     -> Json.arr(),
            "sourceAsText"      -> Json.False,
            "includeMetadata"   -> Json.True,
            "includeDeprecated" -> Json.True
          ).deepMerge(
            i.pipeline
              .foldLeft(JsonObject.empty) {
                case (obj, pipeDef) if pipeDef.name == FilterBySchema.name   =>
                  obj.add("resourceSchemas", FilterBySchema.extractTypes(pipeDef).getOrElse(Set.empty[Iri]).asJson)
                case (obj, pipeDef) if pipeDef.name == FilterByType.name     =>
                  obj.add("resourceTypes", FilterByType.extractTypes(pipeDef).getOrElse(Set.empty[Iri]).asJson)
                case (obj, pipeDef) if pipeDef.name == SourceAsText.name     =>
                  obj.add("sourceAsText", Json.True)
                case (obj, pipeDef) if pipeDef.name == DiscardMetadata.name  =>
                  obj.add("includeMetadata", Json.False)
                case (obj, pipeDef) if pipeDef.name == FilterDeprecated.name =>
                  obj.add("includeDeprecated", Json.False)
                case (obj, _)                                                =>
                  obj
              }
          )
      }

    Encoder.encodeJsonObject.contramapObject[ElasticSearchView] { e =>
      deriveConfiguredEncoder[ElasticSearchView]
        .encodeObject(e)
        .deepMerge(encodeLegacyFields(e))
        .add(keywords.tpe, e.tpe.types.asJson)
        .remove("tags")
        .mapAllKeys("context", _.noSpaces.asJson)
        .mapAllKeys("mapping", _.noSpaces.asJson)
        .mapAllKeys("settings", _.noSpaces.asJson)
        .remove("source")
        .remove("project")
        .remove("id")
        .addContext(e.source.topContextValueOrEmpty.excludeRemoteContexts.contextObj)
    }
  }

  // TODO: Since we are lacking support for `@type: json` (coming in Json-LD 1.1) we have to hack our way into
  // formatting the mapping and settings fields as pure json. This doesn't make sense from the Json-LD 1.0 perspective, though
  implicit val elasticSearchViewJsonLdEncoder: JsonLdEncoder[ElasticSearchView] = {
    val underlying: JsonLdEncoder[ElasticSearchView] = JsonLdEncoder.computeFromCirce(_.id, context)

    new JsonLdEncoder[ElasticSearchView] {

      private def parseJson(jsonString: Json) = jsonString.asString.fold(jsonString)(parse(_).getOrElse(jsonString))

      private def stringToJson(obj: JsonObject) =
        obj
          .mapAllKeys("context", parseJson)
          .mapAllKeys("mapping", parseJson)
          .mapAllKeys("settings", parseJson)

      override def context(value: ElasticSearchView): ContextValue = underlying.context(value)

      override def expand(
          value: ElasticSearchView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        underlying.expand(value)

      override def compact(
          value: ElasticSearchView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        underlying.compact(value).map(c => c.copy(obj = stringToJson(c.obj)))
    }
  }

  implicit private val elasticSearchMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject.empty.addIfExists("_uuid", meta.uuid))

  implicit val elasticSearchMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.elasticsearchMetadata))
}
