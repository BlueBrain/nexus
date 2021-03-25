package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
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
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return the tag -> rev mapping
    */
  def tags: Map[TagLabel, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: Json

  /**
    * @return [[ElasticSearchView]] metadata
    */
  def metadata: Metadata

  /**
    * @return the ElasticSearch view type
    */
  def tpe: ElasticSearchViewType
}

object ElasticSearchView {

  /**
    * An ElasticSearch view that controls the projection of resource events to an ElasticSearch index.
    *
    * @param id                the view id
    * @param project           a reference to the parent project
    * @param uuid              the unique view identifier
    * @param resourceSchemas   the set of schemas considered that constrains resources; empty implies all
    * @param resourceTypes     the set of resource types considered for indexing; empty implies all
    * @param resourceTag       an optional tag to consider for indexing; when set, all resources that are tagged with
    *                          the value of the field are indexed with the corresponding revision
    * @param sourceAsText      whether to include the source of the resource as a text field in the document
    * @param includeMetadata   whether to include the metadata of the resource as individual fields in the document
    * @param includeDeprecated whether to consider deprecated resources for indexing
    * @param mapping           the elasticsearch mapping to be used in order to create the index
    * @param settings          the elasticsearch optional settings to be used in order to create the index
    * @param permission        the permission required for querying this view
    * @param tags              the collection of tags for this resource
    * @param source            the original json value provided by the caller
    */
  final case class IndexingElasticSearchView(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      sourceAsText: Boolean,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      mapping: Json,
      settings: Option[Json],
      permission: Permission,
      tags: Map[TagLabel, Long],
      source: Json
  ) extends ElasticSearchView {
    override def metadata: Metadata = Metadata(Some(uuid))

    override def tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
  }

  /**
    * An ElasticSearch view that delegates queries to multiple indices.
    *
    * @param id      the view id
    * @param project a reference to the parent project
    * @param views   the collection of views where queries will be delegated (if necessary permissions are met)
    * @param tags    the collection of tags for this resource
    * @param source  the original json value provided by the caller
    */
  final case class AggregateElasticSearchView(
      id: Iri,
      project: ProjectRef,
      views: NonEmptySet[ViewRef],
      tags: Map[TagLabel, Long],
      source: Json
  ) extends ElasticSearchView {
    override def metadata: Metadata         = Metadata(None)
    override def tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
  }

  /**
    * ElasticSearchView metadata.
    *
    * @param uuid  the optionally available unique view identifier
    */
  final case class Metadata(uuid: Option[UUID])

  val context: ContextValue = ContextValue(contexts.elasticsearch)

  @nowarn("cat=unused")
  implicit val elasticSearchViewEncoder: Encoder.AsObject[ElasticSearchView] = {
    implicit val config: Configuration                     = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[TagLabel, Long]] = Encoder.instance(_ => Json.Null)

    Encoder.encodeJsonObject.contramapObject[ElasticSearchView] { e =>
      deriveConfiguredEncoder[ElasticSearchView]
        .encodeObject(e)
        .add(keywords.tpe, e.tpe.types.asJson)
        .remove("tags")
        .remove("mapping")
        .remove("settings")
        .remove("source")
        .remove("project")
        .remove("id")
    }
  }

  // TODO: Since we are lacking support for `@type: json` (coming in Json-LD 1.1) we have to hack our way into
  // formatting the mapping and settings fields as pure json. This doesn't make sense from the Json-LD 1.0 perspective, though
  implicit val elasticSearchViewJsonLdEncoder: JsonLdEncoder[ElasticSearchView] = {
    val underlying: JsonLdEncoder[ElasticSearchView] = JsonLdEncoder.computeFromCirce(_.id, context)

    new JsonLdEncoder[ElasticSearchView] {

      private def addPlainJsonKeys(v: IndexingElasticSearchView, obj: JsonObject) =
        obj.add("mapping", v.mapping).addIfExists("settings", v.settings)

      override def context(value: ElasticSearchView): ContextValue = underlying.context(value)

      override def expand(
          value: ElasticSearchView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        value match {
          case v: IndexingElasticSearchView  =>
            underlying.expand(value).flatMap { e =>
              IO.fromOption(e.entries.headOption, UnexpectedJsonLd("A view Json-LD format must have one JsonObject"))
                .map { case (iri, obj) => ExpandedJsonLd.unsafe(iri, addPlainJsonKeys(v, obj)) }
            }
          case _: AggregateElasticSearchView =>
            underlying.expand(value)
        }

      override def compact(
          value: ElasticSearchView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        value match {
          case v: IndexingElasticSearchView  =>
            underlying.compact(value).map(c => c.copy(obj = addPlainJsonKeys(v, c.obj)))
          case _: AggregateElasticSearchView =>
            underlying.compact(value)
        }
    }
  }

  implicit private val elasticSearchMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject.empty.addIfExists("_uuid", meta.uuid))

  implicit val elasticSearchMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.elasticsearchMetadata))
}
