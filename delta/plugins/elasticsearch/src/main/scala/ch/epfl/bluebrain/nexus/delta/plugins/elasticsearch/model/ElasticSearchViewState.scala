package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.implicits.toFunctorOps
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{model, ElasticSearchViews}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonLdEncoderSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{UniformScopedState, UniformScopedStateEncoder}
import com.typesafe.scalalogging.Logger
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe._
import monix.bio.{Task, UIO}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

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
final case class ElasticSearchViewState(
    id: Iri,
    project: ProjectRef,
    uuid: UUID,
    value: ElasticSearchViewValue,
    source: Json,
    tags: Tags,
    rev: Int,
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

  def toResource(
      mappings: ApiMappings,
      base: ProjectBase,
      defaultMapping: JsonObject,
      defaultSettings: JsonObject
  ): ViewResource = {
    ResourceF(
      id = id,
      uris = ResourceUris("views", project, id)(mappings, base),
      rev = rev.toLong,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = asElasticSearchView(defaultMapping, defaultSettings)
    )
  }
}

object ElasticSearchViewState {
  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ElasticSearchViewState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                               = Serializer.circeConfiguration
    implicit val elasticSearchValueEncoder: Encoder[ElasticSearchViewValue] =
      deriveConfiguredEncoder[ElasticSearchViewValue].mapJson(_.deepDropNullValues)
    implicit val elasticSearchValueDecoder: Decoder[ElasticSearchViewValue] =
      deriveConfiguredDecoder[ElasticSearchViewValue]
    implicit val codec: Codec.AsObject[ElasticSearchViewState]              = deriveConfiguredCodec[ElasticSearchViewState]
    Serializer(_.id)
  }

  private val logger: Logger = Logger[ElasticSearchViewState]

  def elasticSearchViewUniformScopedStateEncoder(fetchContext: FetchContext[ContextRejection])(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      baseUri: BaseUri,
      rcr: RemoteContextResolution
  ): UniformScopedStateEncoder[ElasticSearchViewState] =
    UniformScopedStateEncoder(
      ElasticSearchViews.entityType,
      serializer.codec,
      state =>
        fetchContext
          .mapRejection(ProjectContextRejection)
          .onRead(state.project)
          .onErrorHandleWith { rejection =>
            val msg =
              s"Unable to retrieve project context for resource '${state.project}/${state.id}', due to '${rejection.reason}'"
            UIO.delay(logger.error(msg)) >> Task.raiseError(new IllegalArgumentException(msg))
          }
          .flatMap { ctx =>
            val resourceEncoder = implicitly[JsonLdEncoder[ViewResource]]
            for {
              defaultMapping    <- defaultElasticsearchMapping
              defaultSettings   <- defaultElasticsearchSettings
              resource           = state.toResource(ctx.apiMappings, ctx.base, defaultMapping, defaultSettings)
              id                 = resource.resolvedId
              graph             <- resourceEncoder.graph(resource)
              rootGraph          = graph.replaceRootNode(id)
              resourceMetaGraph <- resource.void.toGraph
              rootMetaGraph      = Graph.empty(id) ++ resourceMetaGraph
              typesGraph         = rootMetaGraph.rootTypesGraph
              finalRootGraph     = rootGraph -- rootMetaGraph ++ typesGraph
            } yield UniformScopedState(
              tpe = ElasticSearchViews.entityType,
              project = state.project,
              id = id,
              rev = state.rev,
              deprecated = state.deprecated,
              schema = state.schema,
              types = state.types,
              graph = finalRootGraph,
              metadataGraph = rootMetaGraph,
              source = state.source
            )
          }
    )
}
