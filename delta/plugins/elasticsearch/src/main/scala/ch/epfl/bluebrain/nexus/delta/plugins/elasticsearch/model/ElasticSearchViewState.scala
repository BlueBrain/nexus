package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views.DefaultIndexDef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceAccess, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.*
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import java.util.UUID

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
  * @param indexingRev
  *   the current indexing revision of the view
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
final case class ElasticSearchViewState(
    id: Iri,
    project: ProjectRef,
    uuid: UUID,
    value: ElasticSearchViewValue,
    source: Json,
    tags: Tags,
    rev: Int,
    indexingRev: IndexingRev,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  override def schema: ResourceRef = model.schema

  override def types: Set[Iri] = value.tpe.types

  /**
    * Maps the current state to an [[ElasticSearchView]] value.
    */
  def asElasticSearchView(defaultDef: DefaultIndexDef): ElasticSearchView =
    value match {
      case IndexingElasticSearchViewValue(
            name,
            description,
            resourceTag,
            pipeline,
            mapping,
            settings,
            context,
            permission
          ) =>
        IndexingElasticSearchView(
          id = id,
          name = name,
          description = description,
          project = project,
          uuid = uuid,
          resourceTag = resourceTag,
          pipeline = pipeline,
          mapping = mapping.getOrElse(defaultDef.mapping),
          settings = settings.getOrElse(defaultDef.settings),
          context = context,
          permission = permission,
          tags = tags,
          source = source
        )
      case AggregateElasticSearchViewValue(name, description, views) =>
        AggregateElasticSearchView(
          id = id,
          name = name,
          description = description,
          project = project,
          views = views,
          tags = tags,
          source = source
        )
    }

  def toResource(defaultDef: DefaultIndexDef): ViewResource = {
    ResourceF(
      id = id,
      access = ResourceAccess("views", project, id),
      rev = rev,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = asElasticSearchView(defaultDef)
    )
  }
}

object ElasticSearchViewState {

  implicit val serializer: Serializer[Iri, ElasticSearchViewState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database.*
    import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.Database.*
    implicit val configuration: Configuration                  = Serializer.circeConfiguration
    implicit val codec: Codec.AsObject[ElasticSearchViewState] = deriveConfiguredCodec[ElasticSearchViewState]
    Serializer.dropNullsInjectType()
  }
}
