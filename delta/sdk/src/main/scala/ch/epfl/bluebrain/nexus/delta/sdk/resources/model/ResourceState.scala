package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import cats.implicits.toFunctorOps
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonLdEncoderSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{UniformScopedState, UniformScopedStateEncoder}
import com.typesafe.scalalogging.Logger
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}
import monix.bio.{Task, UIO}

import java.time.Instant
import scala.annotation.nowarn

/**
  * A resource active state.
  *
  * @param id
  *   the resource identifier
  * @param project
  *   the project where the resource belongs
  * @param schemaProject
  *   the project where the schema belongs
  * @param source
  *   the representation of the resource as posted by the subject
  * @param compacted
  *   the compacted JSON-LD representation of the resource
  * @param expanded
  *   the expanded JSON-LD representation of the resource
  * @param rev
  *   the organization revision
  * @param deprecated
  *   the deprecation status of the organization
  * @param schema
  *   the optional schema used to constrain the resource
  * @param types
  *   the collection of known resource types
  * @param tags
  *   the collection of tag aliases
  * @param createdAt
  *   the instant when the organization was created
  * @param createdBy
  *   the identity that created the organization
  * @param updatedAt
  *   the instant when the organization was last updated
  * @param updatedBy
  *   the identity that last updated the organization
  */
final case class ResourceState(
    id: Iri,
    project: ProjectRef,
    schemaProject: ProjectRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd,
    rev: Int,
    deprecated: Boolean,
    schema: ResourceRef,
    types: Set[Iri],
    tags: Tags,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  def toResource(mappings: ApiMappings, base: ProjectBase): DataResource =
    ResourceF(
      id = id,
      uris = ResourceUris.resource(project, schemaProject, id, schema)(mappings, base),
      rev = rev.toLong,
      types = types,
      schema = schema,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      value = Resource(id, project, tags, schema, source, compacted, expanded)
    )
}

object ResourceState {

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ResourceState] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration         = Serializer.circeConfiguration
    implicit val codec: Codec.AsObject[ResourceState] = deriveConfiguredCodec[ResourceState]
    Serializer(_.id)
  }

  private val logger: Logger = Logger[ResourceState]
  def resourceUniformScopedStateEncoder(fetchContext: FetchContext[ContextRejection])(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      baseUri: BaseUri,
      rcr: RemoteContextResolution
  ): UniformScopedStateEncoder[ResourceState] = {
    UniformScopedStateEncoder[ResourceState](
      Resources.entityType,
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
            val resourceEncoder = implicitly[JsonLdEncoder[Resource]]
            val resource        = state.toResource(ctx.apiMappings, ctx.base)
            val id              = resource.resolvedId
            for {
              graph             <- resourceEncoder.graph(resource.value)
              rootGraph          = graph.replaceRootNode(id)
              resourceMetaGraph <- resource.void.toGraph
              rootMetaGraph      = Graph.empty(id) ++ resourceMetaGraph
              typesGraph         = rootMetaGraph.rootTypesGraph
              finalRootGraph     = rootGraph -- rootMetaGraph ++ typesGraph
            } yield UniformScopedState(
              tpe = Resources.entityType,
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

}
