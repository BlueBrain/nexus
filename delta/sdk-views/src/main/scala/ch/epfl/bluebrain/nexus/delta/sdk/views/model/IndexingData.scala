package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.subject
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.{EventExchangeResult, EventExchangeValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.IO
import org.apache.jena.graph.Node

/**
  * Representation of indexing data.
  */
sealed trait IndexingData {

  def id: Iri

  def discardSource: IndexingData
}

object IndexingData {

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  /**
    * Representation of resource for indexing
    *
    * @param id
    *   the resource id
    * @param deprecated
    *   whether the resource is deprecated
    * @param schema
    *   the resource schema
    * @param types
    *   the resource types
    * @param graph
    *   the graph with non-metadata predicates
    * @param metadataGraph
    *   the graph with the metadata value triples
    * @param source
    *   the original payload of the resource posted by the caller
    */
  final case class IndexingResource(
      id: Iri,
      deprecated: Boolean,
      schema: ResourceRef,
      types: Set[Iri],
      graph: Graph,
      metadataGraph: Graph,
      source: Json
  ) extends IndexingData {

    def discardSource: IndexingResource = copy(source = Json.obj())
  }

  /**
    * Representation of a resource which was not found using a tag
    *
    * @param id
    *   the id of the resource
    */
  final case class TagNotFound(id: Iri) extends IndexingData {
    override def discardSource: TagNotFound = this
  }

  object IndexingResource {

    def apply[A, M](
        exchangedValue: EventExchangeValue[A, M]
    )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingResource] = {

      val resource = exchangedValue.value.resource
      val encoder  = exchangedValue.value.encoder
      val source   = exchangedValue.value.source
      val metadata = exchangedValue.metadata
      val id       = resource.resolvedId
      for {
        graph             <- encoder.graph(resource.value)
        rootGraph          = graph.replaceRootNode(id)
        resourceMetaGraph <- resource.void.toGraph
        metaGraph         <- metadata.encoder.graph(metadata.value)
        rootMetaGraph      = metaGraph.replaceRootNode(id) ++ resourceMetaGraph
        typesGraph         = rootMetaGraph.rootTypesGraph
        finalRootGraph     = rootGraph -- rootMetaGraph ++ typesGraph
      } yield IndexingResource(
        resource.resolvedId,
        resource.deprecated,
        resource.schema,
        resource.types,
        finalRootGraph,
        rootMetaGraph,
        source.removeAllKeys(keywords.context)
      )
    }
  }

  /**
    * Helper function to generate an IndexingData from the [[EventExchangeResult]]. The resource data is divided in 2
    * graphs. One containing only metadata and the other containing only data from the predicates present in
    * ''graphPredicates''.
    */
  def apply(
      exchangeResult: EventExchangeResult,
      graphPredicates: Set[Node]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingData] = exchangeResult match {
    case EventExchange.TagNotFound(id)            => IO.pure(TagNotFound(id))
    case exchangedValue: EventExchangeValue[_, _] =>
      IndexingResource(exchangedValue).map { data =>
        val id = subject(data.id)
        data.copy(graph = data.graph.filter { case (s, p, _) => s == id && graphPredicates.contains(p) })
      }
  }

  /**
    * Helper function to generate an IndexingData from the [[EventExchangeValue]]. The resource data is divided in 2
    * graphs. One containing only metadata and the other containing only data.
    */
  def apply(
      exchangeResult: EventExchangeResult
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingData] = {
    exchangeResult match {
      case EventExchange.TagNotFound(id)            => IO.pure(TagNotFound(id))
      case exchangedValue: EventExchangeValue[_, _] => IndexingResource(exchangedValue)
    }
  }

}
