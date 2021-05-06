package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.subject
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.IO
import org.apache.jena.graph.Node

/**
  * ElasticSearch indexing data
  *
  * @param id                    the resource id
  * @param deprecated            whether the resource is deprecated
  * @param schema                the resource schema
  * @param types                 the resource types
  * @param graph                 the graph with non-metadata predicates
  * @param metadataGraph         the graph with the metadata value triples
  * @param source                the original payload of the resource posted by the caller
  */
final case class IndexingData(
    id: Iri,
    deprecated: Boolean,
    schema: ResourceRef,
    types: Set[Iri],
    graph: Graph,
    metadataGraph: Graph,
    source: Json
) {

  def discardSource: IndexingData = copy(source = Json.obj())
}

object IndexingData {

  /**
    * Helper function to generate an IndexingData from the [[EventExchangeValue]].
    * The resource data is divided in 2 graphs. One containing only metadata and the other containing only data
    * from the predicates present in ''graphPredicates''.
    *
    * @tparam A the value type
    * @tparam M the metadata type
    */
  def apply[A, M](
      exchangedValue: EventExchangeValue[A, M],
      graphPredicates: Set[Node]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingData] =
    IndexingData(exchangedValue).map { data =>
      val id = subject(data.id)
      data.copy(graph = data.graph.filter { case (s, p, _) => s == id && graphPredicates.contains(p) })
    }

  /**
    * Helper function to generate an IndexingData from the [[EventExchangeValue]].
    * The resource data is divided in 2 graphs. One containing only metadata and the other containing only data.
    *
    * @tparam A the value type
    * @tparam M the metadata type
    */
  def apply[A, M](
      exchangedValue: EventExchangeValue[A, M]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingData] = {
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
    } yield IndexingData(resource, finalRootGraph, rootMetaGraph, source.removeAllKeys(keywords.context))
  }

  def apply(resource: ResourceF[_], graph: Graph, metadataGraph: Graph, source: Json)(implicit
      baseUri: BaseUri
  ): IndexingData =
    IndexingData(
      resource.resolvedId,
      resource.deprecated,
      resource.schema,
      resource.types,
      graph,
      metadataGraph,
      source
    )
}
