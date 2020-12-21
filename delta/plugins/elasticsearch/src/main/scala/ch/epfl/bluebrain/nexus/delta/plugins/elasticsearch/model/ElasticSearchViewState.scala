package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, TagLabel}
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of ElasticSearchView state types.
  */
trait ElasticSearchViewState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[ElasticSearchViewResource]
}

object ElasticSearchViewState {

  /**
    * Initial state of an ElasticSearch view.
    */
  final case object Initial extends ElasticSearchViewState {
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ElasticSearchViewResource] = None
  }

  /**
    * State for an existing ElasticSearch view.
    *
    * @param id         the view id
    * @param project    a reference to the parent project
    * @param uuid       the unique view identifier
    * @param value      the view configuration
    * @param source     the last original json value provided by the caller
    * @param tags       the collection of tags
    * @param rev        the current revision of the view
    * @param deprecated the deprecation status of the view
    * @param createdAt  the instant when the view was created
    * @param createdBy  the subject that created the view
    * @param updatedAt  the instant when the view was last updated
    * @param updatedBy  the subject that last updated the view
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      tags: Map[TagLabel, Long],
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
    lazy val asElasticSearchView: ElasticSearchView = value match {
      case IndexingElasticSearchViewValue(
            resourceSchemas,
            resourceTypes,
            resourceTag,
            sourceAsText,
            includeMetadata,
            includeDeprecated,
            mapping,
            permission
          ) =>
        IndexingElasticSearchView(
          id = id,
          project = project,
          uuid = uuid,
          resourceSchemas = resourceSchemas,
          resourceTypes = resourceTypes,
          resourceTag = resourceTag,
          sourceAsText = sourceAsText,
          includeMetadata = includeMetadata,
          includeDeprecated = includeDeprecated,
          mapping = mapping,
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

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ElasticSearchViewResource] = {
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris("views", project, id)(mappings, base),
          rev = rev,
          types = Set(value.tpe.iri),
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = asElasticSearchView
        )
      )
    }
  }

}
