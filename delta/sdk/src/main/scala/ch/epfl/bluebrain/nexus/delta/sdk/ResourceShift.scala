package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import io.circe.Json

/**
  * Defines common operations to retrieve the different resources in a common format for tasks like indexing or
  * resolving operations.
  *
  * Plugins can provide implementations for the resource types they introduce so that they can also be handled properly.
  *
  * @param entityType
  *   the entity type for the current resource
  * @param fetchResource
  *   how to fetch this resource given a reference
  * @param valueEncoder
  *   how to encode it in JSON-LD
  * @param metadataEncoder
  *   how to encode its metadata in Json-LD if any
  * @param serializer
  *   how to decode its states from the database
  * @tparam State
  *   the state associated to the resource
  * @tparam A
  *   the resource type
  * @tparam M
  *   the resource metadata type
  */
abstract class ResourceShift[State <: ScopedState, A, M](
    val entityType: EntityType,
    fetchResource: (ResourceRef, ProjectRef) => IO[Option[ResourceF[A]]],
    valueEncoder: JsonLdEncoder[A],
    metadataEncoder: Option[JsonLdEncoder[M]]
)(implicit serializer: Serializer[_, State], baseUri: BaseUri) {

  implicit private val api: JsonLdApi                                         = JsonLdJavaApi.lenient
  implicit private val valueJsonLdEncoder: JsonLdEncoder[A]                   = valueEncoder
  implicit private val resourceFJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] = ResourceF.defaultResourceFAJsonLdEncoder

  protected def toResourceF(state: State): ResourceF[A]

  /**
    * Fetch the resource from its reference
    *
    * @return
    *   the resource with its original source, its metadata and its encoder
    */
  def fetch(reference: ResourceRef, project: ProjectRef): IO[Option[JsonLdContent[A, M]]] =
    fetchResource(reference, project).map(_.map(resourceToContent))

  protected def resourceToContent(value: ResourceF[A]): JsonLdContent[A, M]

  /**
    * Retrieves a [[GraphResource]] from the json payload stored in database.
    */
  def toGraphResource(json: Json)(implicit
      cr: RemoteContextResolution
  ): IO[GraphResource] =
    for {
      state   <- IO.fromEither(serializer.codec.decodeJson(json))
      resource = toResourceF(state)
      graph   <- toGraphResource(state.project, resource)
    } yield graph

  def toGraphResourceElem(project: ProjectRef, resource: ResourceF[A])(implicit
      cr: RemoteContextResolution
  ): IO[Elem[GraphResource]] = toGraphResource(project, resource).redeem(
    err => FailedElem(entityType, resource.id, Some(project), resource.updatedAt, Offset.Start, err, resource.rev),
    graph => SuccessElem(entityType, resource.id, Some(project), resource.updatedAt, Offset.Start, graph, resource.rev)
  )

  private def toGraphResource(project: ProjectRef, resource: ResourceF[A])(implicit
      cr: RemoteContextResolution
  ): IO[GraphResource] = {
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
      project = project,
      id = id,
      rev = resource.rev,
      deprecated = resource.deprecated,
      schema = resource.schema,
      types = resource.types,
      graph = finalRootGraph,
      metadataGraph = rootMetaGraph,
      source = content.source.removeAllKeys(keywords.context)
    )
  }

  private def encodeMetadata(id: Iri, metadata: Option[M])(implicit cr: RemoteContextResolution, opts: JsonLdOptions) =
    (metadata, metadataEncoder) match {
      case (Some(m), Some(e)) => e.graph(m).map { g => Some(g.replaceRootNode(id)) }
      case (_, _)             => IO.none
    }

}

object ResourceShift {

  /**
    * Create a [[ResourceShift]] for a resource defining custom metadata
    * @param entityType
    *   the entity type of the resource
    * @param fetchResource
    *   how to fetch this resource from a reference
    * @param stateToResource
    *   the function to pass from its state to a [[ResourceF[A]] ]
    * @param asContent
    *   the function to pass from a [[ResourceF[A]] ] to a common [[JsonLdContent]]
    * @param serializer
    *   the database serializer
    * @param valueEncoder
    *   the JSON-LD encoder for the resource
    * @param metadataEncoder
    *   the JSON-LD encoder for its metadata
    */
  def withMetadata[State <: ScopedState, A, M](
      entityType: EntityType,
      fetchResource: (ResourceRef, ProjectRef) => IO[ResourceF[A]],
      stateToResource: State => ResourceF[A],
      asContent: ResourceF[A] => JsonLdContent[A, M]
  )(implicit
      serializer: Serializer[_, State],
      valueEncoder: JsonLdEncoder[A],
      metadataEncoder: JsonLdEncoder[M],
      baseUri: BaseUri
  ): ResourceShift[State, A, M] =
    new ResourceShift[State, A, M](
      entityType,
      fetchResource(_, _).redeem(_ => None, Some(_)),
      valueEncoder,
      Some(metadataEncoder)
    ) {

      override protected def toResourceF(state: State): ResourceF[A] = stateToResource(state)

      override protected def resourceToContent(value: ResourceF[A]): JsonLdContent[A, M] = asContent(value)
    }

  /**
    * Create a [[ResourceShift]] for a resource without any custom metadata
    * @param entityType
    *   the entity type of the resource
    * @param fetchResource
    *   how to fetch this resource from a reference
    * @param stateToResource
    *   the function to pass from its state to a [[ResourceF[A]] ]
    * @param asContent
    *   the function to pass from a [[ResourceF[A]] ] to a common [[JsonLdContent]]
    * @param serializer
    *   the database serializer
    * @param valueEncoder
    *   the JSON-LD encoder for the resource
    */
  def apply[State <: ScopedState, B](
      entityType: EntityType,
      fetchResource: (ResourceRef, ProjectRef) => IO[ResourceF[B]],
      stateToResource: State => ResourceF[B],
      asContent: ResourceF[B] => JsonLdContent[B, Nothing]
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
      override protected def toResourceF(state: State): ResourceF[B] =
        stateToResource(state)

      override protected def resourceToContent(value: ResourceF[B]): JsonLdContent[B, Nothing] = asContent(value)
    }
}
