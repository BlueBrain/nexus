package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{schema, BlazegraphViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, TagLabel}
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of BlazegraphView states.
  */
trait BlazegraphViewState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[BlazegraphViewResource]

  /**
    * @return the current state revision
    */
  def rev: Long
}

object BlazegraphViewState {

  /**
    * Initial state of a Blazegraph view.
    */
  final case object Initial extends BlazegraphViewState {
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[BlazegraphViewResource] = None
    override def rev: Long                                                                            = 0L
  }

  /**
    * State for an existing Blazegraph view.
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
      value: BlazegraphViewValue,
      source: Json,
      tags: Map[TagLabel, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends BlazegraphViewState {

    /**
      * Maps the current state to a [[BlazegraphView]].
      */
    lazy val asBlazegraphView: BlazegraphView                                                         = value match {
      case IndexingBlazegraphViewValue(
            resourceSchemas,
            resourceTypes,
            resourceTag,
            includeMetadata,
            includeDeprecated,
            permission
          ) =>
        IndexingBlazegraphView(
          id,
          project,
          uuid,
          resourceSchemas,
          resourceTypes,
          resourceTag,
          includeMetadata,
          includeDeprecated,
          permission,
          tags,
          source
        )
      case AggregateBlazegraphViewValue(views) =>
        AggregateBlazegraphView(id, project, views, tags, source)
    }
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[BlazegraphViewResource] = Some(
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
        value = asBlazegraphView
      )
    )
  }

  implicit val revisionLens: Lens[BlazegraphViewState, Long] = (s: BlazegraphViewState) => s.rev
}
