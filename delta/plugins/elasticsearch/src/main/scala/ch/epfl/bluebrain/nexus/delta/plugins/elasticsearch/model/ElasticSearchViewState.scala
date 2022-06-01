package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of ElasticSearchView state types.
  */
sealed trait ElasticSearchViewState extends Product with Serializable {

  /**
    * @return
    *   the current view revision
    */
  def rev: Long

  /**
    * Converts the state into a resource representation.
    */
  def toResource(
      mappings: ApiMappings,
      base: ProjectBase,
      defaultMapping: JsonObject,
      defaultSettings: JsonObject
  ): Option[ViewResource]
}

object ElasticSearchViewState {

  /**
    * Initial state of an ElasticSearch view.
    */
  final case object Initial extends ElasticSearchViewState {
    override val rev: Long  = 0L
    override def toResource(
        mappings: ApiMappings,
        base: ProjectBase,
        defaultMapping: JsonObject,
        defaultSettings: JsonObject
    ): Option[ViewResource] = None
  }

  /**
    * State for an existing ElasticSearch view.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param uuid
    *   the unique view identifier
    * @param value
    *   the view configuration
    * @param source
    *   the last original json value provided by the caller
    * @param tags
    *   the collection of tags
    * @param rev
    *   the current revision of the view
    * @param deprecated
    *   the deprecation status of the view
    * @param createdAt
    *   the instant when the view was created
    * @param createdBy
    *   the subject that created the view
    * @param updatedAt
    *   the instant when the view was last updated
    * @param updatedBy
    *   the subject that last updated the view
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      tags: Map[UserTag, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ElasticSearchViewState {

    /**
      * Maps the current state to an [[ElasticSearchView]] value.
      */
    def asElasticSearchView(defaultMapping: JsonObject, defaultSettings: JsonObject): ElasticSearchView = value match {
      case IndexingElasticSearchViewValue(
            resourceTag,
            pipeline,
            mapping,
            settings,
            context,
            permission
          ) =>
        IndexingElasticSearchView(
          id = id,
          project = project,
          uuid = uuid,
          resourceTag = resourceTag,
          pipeline = pipeline,
          mapping = mapping.getOrElse(defaultMapping),
          settings = settings.getOrElse(defaultSettings),
          context = context,
          permission = permission,
          tags = tags,
          source = source
        )
      case AggregateElasticSearchViewValue(views) =>
        AggregateElasticSearchView(
          id = id,
          project = project,
          views = views,
          tags = tags,
          source = source
        )
    }

    override def toResource(
        mappings: ApiMappings,
        base: ProjectBase,
        defaultMapping: JsonObject,
        defaultSettings: JsonObject
    ): Option[ViewResource] = {
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris("views", project, id)(mappings, base),
          rev = rev,
          types = value.tpe.types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = asElasticSearchView(defaultMapping, defaultSettings)
        )
      )
    }
  }

  implicit val revisionLens: Lens[ElasticSearchViewState, Long] = (s: ElasticSearchViewState) => s.rev

}
