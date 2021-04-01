package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, Task}

final case class BlazegraphIndexingStreamEntry(
    resource: ResourceF[IndexingData]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {

  /**
    * Deletes or indexes the current resource depending on the passed filters
    */
  def deleteOrIndex(
      includeMetadata: Boolean,
      includeDeprecated: Boolean
  ): Task[SparqlWriteQuery] =
    if (deleteCandidate(includeDeprecated)) delete()
    else index(includeMetadata)

  /**
    * Deletes the current resource named graph (and all its triples)
    */
  def delete(): Task[SparqlWriteQuery] =
    namedGraph(resource.id).map(SparqlWriteQuery.drop)

  /**
    * Generates an Sparql replace query with all the triples to be added to the resource named graph
    */
  def index(includeMetadata: Boolean): Task[SparqlWriteQuery] =
    for {
      triples    <- toTriples(includeMetadata)
      namedGraph <- namedGraph(resource.id)
    } yield SparqlWriteQuery.replace(namedGraph, triples)

  /**
    * Checks if the current resource is candidate to be deleted
    */
  def deleteCandidate(includeDeprecated: Boolean): Boolean =
    resource.deprecated && !includeDeprecated

  /**
    * Checks if the current resource contains some of the schemas passed as ''resourceSchemas''
    */
  def containsSchema(resourceSchemas: Set[Iri]): Boolean =
    resourceSchemas.isEmpty || resourceSchemas.contains(resource.schema.iri)

  /**
    * Checks if the current resource contains some of the types passed as ''resourceTypes''
    */
  def containsTypes[A](resourceTypes: Set[Iri]): Boolean =
    resourceTypes.isEmpty || resourceTypes.intersect(resource.types).nonEmpty

  private def toTriples(includeMetadata: Boolean): Task[NTriples] =
    for {
      metadataGraph <- if (includeMetadata) resource.void.toGraph.map(_ ++ resource.value.metadataGraph)
                       else Task.delay(Graph.empty)
      fullGraph      = resource.value.graph ++ metadataGraph
      nTriples      <- IO.fromEither(fullGraph.toNTriples)
    } yield nTriples

  private def namedGraph[A](id: Iri): Task[Uri] =
    IO.fromEither((id / "graph").toUri).mapError(_ => InvalidIri)

}

object BlazegraphIndexingStreamEntry {

  /**
    * Converts the resource retrieved from an event exchange to [[BlazegraphIndexingStreamEntry]].
    * It generates an [[IndexingData]] for blazegraph indexing
    */
  def fromEventExchange[A, M](
      exchangedValue: EventExchangeValue[A, M]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): Task[BlazegraphIndexingStreamEntry] = {
    val resource = exchangedValue.value.toResource
    val encoder  = exchangedValue.value.encoder
    val metadata = exchangedValue.metadata
    val id       = resource.resolvedId
    for {
      graph        <- encoder.graph(resource.value)
      rootGraph     = graph.replaceRootNode(id)
      metaGraph    <- metadata.encoder.graph(metadata.value)
      rootMetaGraph = metaGraph.replaceRootNode(id)
      data          = resource.as(IndexingData(rootGraph, rootMetaGraph))
    } yield BlazegraphIndexingStreamEntry(data)
  }
}
