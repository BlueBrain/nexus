package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, MissingPredicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NQuads, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataPredicates, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.Json
import monix.bio.{IO, Task}

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
    IO.fromEither(id.toUri).mapError(_ => InvalidIri)

}

object BlazegraphIndexingStreamEntry {

  /**
    * Converts the resource retrieved from an event exchange to [[BlazegraphIndexingStreamEntry]].
    * It generates an [[IndexingData]] for blazegraph indexing
    */
  def fromEventExchange[A, M](
      exchangedValue: EventExchangeValue[A, M]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): Task[BlazegraphIndexingStreamEntry] =
    IndexingData(exchangedValue).map(BlazegraphIndexingStreamEntry(_))

  /**
    * Converts the resource in n-quads format to [[BlazegraphIndexingStreamEntry]]
    */
  def fromNQuads(
      id: Iri,
      nQuads: NQuads,
      metadataPredicates: MetadataPredicates
  ): Either[RdfError, BlazegraphIndexingStreamEntry] = {
    for {
      graph      <- Graph(nQuads)
      valueGraph  = graph.filter { case (_, p, _) => !metadataPredicates.values.contains(p) }
      metaGraph   = graph.filter { case (_, p, _) => metadataPredicates.values.contains(p) }
      types       = graph.rootTypes
      schema     <- metaGraph
                      .find(id, nxv.constrainedBy.iri)
                      .map(triple => ResourceRef(iri"${triple.getURI}"))
                      .toRight(MissingPredicate(nxv.constrainedBy.iri))
      deprecated <- metaGraph
                      .find(id, nxv.deprecated.iri)
                      .map(_.getLiteralLexicalForm.toBoolean)
                      .toRight(MissingPredicate(nxv.deprecated.iri))
    } yield BlazegraphIndexingStreamEntry(
      IndexingData(id, deprecated, schema, types, valueGraph, metaGraph, Json.obj())
    )
  }
}
