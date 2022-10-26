package ch.epfl.bluebrain.nexus.delta.sdk

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.Json
import monix.bio.{IO, Task, UIO}

abstract class ResourceShift[State <: ScopedState, B, M](
    val entityType: EntityType,
    fetchResource: (ResourceRef, ProjectRef) => UIO[Option[ResourceF[B]]],
    valueEncoder: JsonLdEncoder[B],
    metadataEncoder: Option[JsonLdEncoder[M]]
)(implicit serializer: Serializer[_, State], baseUri: BaseUri) {

  implicit private val api: JsonLdApi                                         = JsonLdJavaApi.lenient
  implicit private val valueJsonLdEncoder: JsonLdEncoder[B]                   = valueEncoder
  implicit private val resourceFJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] = ResourceF.defaultResourceFAJsonLdEncoder

  def toResourceF(context: ProjectContext, state: State): ResourceF[B]

  def toJsonLdContent(reference: ResourceRef, project: ProjectRef): UIO[Option[JsonLdContent[B, M]]] =
    fetchResource(reference, project).map(_.map(resourceToContent))

  protected def resourceToContent(value: ResourceF[B]): JsonLdContent[B, M]

  def toGraphResource(json: Json, fetchContext: ProjectRef => UIO[ProjectContext])(implicit
      cr: RemoteContextResolution
  ): Task[GraphResource] =
    for {
      state   <- Task.fromEither(serializer.codec.decodeJson(json))
      context <- fetchContext(state.project)
      graph   <- toGraphResource(context, state)
    } yield graph

  private def toGraphResource(context: ProjectContext, state: State)(implicit
      cr: RemoteContextResolution
  ): IO[RdfError, GraphResource] = {
    val resource = toResourceF(context, state)
    val content  = resourceToContent(resource)
    val metadata = content.metadata
    val id       = resource.resolvedId
    for {
      graph             <- valueJsonLdEncoder.graph(resource.value)
      rootGraph          = graph.replaceRootNode(id)
      resourceMetaGraph <- resourceFJsonLdEncoder.graph(resource.void)
      metaGraph         <- encodeMetadata(id, metadata)
      rootMetaGraph      = metaGraph.fold(resourceMetaGraph)(_ ++ resourceMetaGraph)
      typesGraph         = rootMetaGraph.rootTypesGraph
      finalRootGraph     = rootGraph -- rootMetaGraph ++ typesGraph
    } yield GraphResource(
      tpe = entityType,
      project = state.project,
      id = id,
      rev = state.rev,
      deprecated = state.deprecated,
      schema = state.schema,
      types = state.types,
      graph = finalRootGraph,
      metadataGraph = rootMetaGraph,
      source = content.source
    )
  }

  private def encodeMetadata(id: Iri, metadata: Option[M])(implicit cr: RemoteContextResolution) =
    (metadata, metadataEncoder) match {
      case (Some(m), Some(e)) => e.graph(m).map { g => Some(g.replaceRootNode(id)) }
      case (_, _)             => UIO.none
    }

}

object ResourceShift {

  def withMetadata[State <: ScopedState, B, M](
      entityType: EntityType,
      fetchResource: (ResourceRef, ProjectRef) => IO[_, ResourceF[B]],
      stateToResource: (ProjectContext, State) => ResourceF[B],
      toContent: ResourceF[B] => JsonLdContent[B, M]
  )(implicit
      serializer: Serializer[_, State],
      valueEncoder: JsonLdEncoder[B],
      metadataEncoder: JsonLdEncoder[M],
      baseUri: BaseUri
  ): ResourceShift[State, B, M] =
    new ResourceShift[State, B, M](
      entityType,
      fetchResource(_, _).redeem(_ => None, Some(_)),
      valueEncoder,
      Some(metadataEncoder)
    ) {

      override def toResourceF(context: ProjectContext, state: State): ResourceF[B] = stateToResource(context, state)

      override protected def resourceToContent(value: ResourceF[B]): JsonLdContent[B, M] = toContent(value)
    }

  def apply[State <: ScopedState, B](
      entityType: EntityType,
      fetchResource: (ResourceRef, ProjectRef) => IO[_, ResourceF[B]],
      toResource: (ProjectContext, State) => ResourceF[B],
      toContent: ResourceF[B] => JsonLdContent[B, Nothing]
  )(implicit
      serializer: Serializer[_, State],
      valueEncoder: JsonLdEncoder[B],
      baseUri: BaseUri
  ): ResourceShift[State, B, Nothing] =
    new ResourceShift[State, B, Nothing](
      entityType,
      fetchResource(_, _).redeem(_ => None, Some(_)),
      valueEncoder,
      None
    ) {
      override def toResourceF(context: ProjectContext, state: State): ResourceF[B] = toResource(context, state)

      override protected def resourceToContent(value: ResourceF[B]): JsonLdContent[B, Nothing] = toContent(value)
    }
}
