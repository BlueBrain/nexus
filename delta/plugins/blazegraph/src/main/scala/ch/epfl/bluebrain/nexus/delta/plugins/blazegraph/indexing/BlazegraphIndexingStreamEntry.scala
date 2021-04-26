package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, MissingPredicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NQuads, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Triple}
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, Task}
import org.apache.jena.graph.Node

final case class BlazegraphIndexingStreamEntry(
    resource: IndexingData
) {

  /**
    * Deletes or indexes the current resource depending on the passed filters
    */
  def deleteOrIndex(
      includeMetadata: Boolean,
      includeDeprecated: Boolean
  ): Task[Option[SparqlWriteQuery]] =
    if (deleteCandidate(includeDeprecated)) delete().map(Some.apply)
    else index(includeMetadata)

  /**
    * Deletes the current resource named graph (and all its triples)
    */
  def delete(): Task[SparqlWriteQuery] =
    namedGraph(resource.id).map(SparqlWriteQuery.drop)

  /**
    * Generates an Sparql replace query with all the triples to be added to the resource named graph
    */
  def index(includeMetadata: Boolean): Task[Option[SparqlWriteQuery]] =
    for {
      triples    <- IO.fromEither(toTriples(includeMetadata))
      namedGraph <- namedGraph(resource.id)
    } yield Option.when(triples.value.trim.nonEmpty)(SparqlWriteQuery.replace(namedGraph, triples))

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

  private def toTriples(includeMetadata: Boolean): Either[RdfError, NTriples] = {
    val metadataGraph =
      if (includeMetadata) resource.metadataGraph
      else Graph.empty
    val fullGraph     = resource.graph ++ metadataGraph
    fullGraph.toNTriples
  }

  private def namedGraph[A](id: Iri): Task[Uri] =
    IO.fromEither((id / "graph").toUri).mapError(_ => InvalidIri)

}

object BlazegraphIndexingStreamEntry {

  private val typePredicate       = Triple.predicate(rdf.tpe)

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
      graph             <- encoder.graph(resource.value)
      rootGraph          = graph.replaceRootNode(id)
      metaGraph         <- metadata.encoder.graph(metadata.value)
      resourceMetaGraph <- resource.void.toGraph
      fullMetaGraph      = metaGraph ++ resourceMetaGraph
      rootMetaGraph      = fullMetaGraph.replaceRootNode(id)
      data               = IndexingData(resource, rootGraph, rootMetaGraph)
    } yield BlazegraphIndexingStreamEntry(data)
  }

  /**
    * Converts the resource in n-quads fromat to [[BlazegraphIndexingStreamEntry]]
    */
  def fromNQuads(
      id: Iri,
      nQuads: NQuads,
      metadataPredicates: Set[Node]
  ): Either[RdfError, BlazegraphIndexingStreamEntry] = {

    def findPredicate(idNode: Node, graph: Graph, predicate: Iri): Either[RdfError, Node] = {
      val predicateNode = Triple.predicate(predicate)
      graph
        .find {
          case (s, p, _) if s == idNode && p == predicateNode => true
          case _                                              => false
        }
        .map(_._3)
        .toRight(MissingPredicate(predicate))
    }

    val idNode = Triple.subject(id)
    for {
      graph      <- Graph(nQuads)
      valuegraph  = graph.filter { case (_, p, _) => !metadataPredicates.contains(p) }
      metagraph   = graph.filter { case (_, p, _) => metadataPredicates.contains(p) }
      types       = graph
                      .filter {
                        case (s, p, _) if s == idNode && p == typePredicate => true
                        case _                                              => false
                      }
                      .triples
                      .map(triple => Iri.unsafe(triple._3.getURI))
      schema     <- findPredicate(idNode, metagraph, nxv.constrainedBy.iri)
                      .map(triple => ResourceRef.apply(Iri.unsafe(triple.getURI)))
      deprecated <- findPredicate(idNode, metagraph, nxv.deprecated.iri)
                      .map(_.getLiteralLexicalForm.toBoolean)
    } yield BlazegraphIndexingStreamEntry(IndexingData(id, deprecated, schema, types, valuegraph, metagraph))
  }
}
