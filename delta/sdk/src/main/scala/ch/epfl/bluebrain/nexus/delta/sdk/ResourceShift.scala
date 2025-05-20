package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{obj, predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef, Tags}
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
  * @param serializer
  *   how to decode its states from the database
  * @tparam State
  *   the state associated to the resource
  * @tparam A
  *   the resource type
  */
abstract class ResourceShift[State <: ScopedState, A](
    val entityType: EntityType,
    fetchResource: (ResourceRef, ProjectRef) => IO[Option[ResourceF[A]]],
    valueEncoder: JsonLdEncoder[A]
)(implicit serializer: Serializer[?, State], baseUri: BaseUri) {

  implicit private val api: JsonLdApi                                         = TitaniumJsonLdApi.lenient
  implicit private val valueJsonLdEncoder: JsonLdEncoder[A]                   = valueEncoder
  implicit private val resourceFJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] = ResourceF.defaultResourceFAJsonLdEncoder

  protected def toResourceF(state: State): ResourceF[A]

  /**
    * Fetch the resource from its reference
    *
    * @return
    *   the resource with its original source, its metadata and its encoder
    */
  def fetch(reference: ResourceRef, project: ProjectRef): IO[Option[JsonLdContent[A]]] =
    fetchResource(reference, project).map(_.map(resourceToContent))

  protected def resourceToContent(value: ResourceF[A]): JsonLdContent[A]

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
    err =>
      FailedElem(
        entityType,
        resource.id,
        project,
        resource.updatedAt,
        Offset.Start,
        err,
        resource.rev
      ),
    graph => SuccessElem(entityType, resource.id, project, resource.updatedAt, Offset.Start, graph, resource.rev)
  )

  private def toGraphResource(project: ProjectRef, resource: ResourceF[A])(implicit
      cr: RemoteContextResolution
  ): IO[GraphResource] = {
    val content = resourceToContent(resource)
    val id      = resource.resolvedId
    for {
      rootGraph         <- valueJsonLdEncoder.graph(resource.value)
      resourceMetaGraph <- resourceFJsonLdEncoder.graph(resource.void)
      rootMetaGraph      = resourceMetaGraph.add(encodeTags(id, content.tags))
      typesGraph         = rootMetaGraph.rootTypesNodes
      finalRootGraph     = (rootGraph -- rootMetaGraph).add(typesGraph)
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

  private def encodeTags(id: Iri, tags: Tags): Set[Triple] =
    tags.tags.map { tag =>
      (subject(id), predicate(nxv.tags.iri), obj(tag.value))
    }.toSet

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
    */
  def apply[State <: ScopedState, A](
      entityType: EntityType,
      fetchResource: (ResourceRef, ProjectRef) => IO[ResourceF[A]],
      stateToResource: State => ResourceF[A],
      asContent: ResourceF[A] => JsonLdContent[A]
  )(implicit
      serializer: Serializer[?, State],
      valueEncoder: JsonLdEncoder[A],
      baseUri: BaseUri
  ): ResourceShift[State, A] =
    new ResourceShift[State, A](
      entityType,
      fetchResource(_, _).redeem(_ => None, Some(_)),
      valueEncoder
    ) {

      override protected def toResourceF(state: State): ResourceF[A] = stateToResource(state)

      override protected def resourceToContent(value: ResourceF[A]): JsonLdContent[A] = asContent(value)
    }
}
