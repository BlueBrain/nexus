package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, MissingPredicate}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NQuads, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataPredicates}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.{IndexingData, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import io.circe.Json
import monix.bio.{IO, Task}

final case class BlazegraphIndexingStreamEntry(
    data: ViewData
) {

  def writeOrNone(view: IndexingBlazegraphView): Task[Option[SparqlWriteQuery]] =
    writeOrNone(view.resourceSchemas, view.resourceTypes, view.includeDeprecated, view.includeMetadata)

  def writeOrNone(
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      includeDeprecated: Boolean,
      includeMetadata: Boolean
  ): Task[Option[SparqlWriteQuery]] = data match {
    case TagNotFound(_)         => delete().map(Some(_))
    case resource: IndexingData =>
      deleteCandidateResource(resource, resourceSchemas, resourceTypes, includeDeprecated) match {
        case Some(true)  => delete().map(Some(_))
        case Some(false) => index(resource, includeMetadata)
        case None        => Task.none
      }
  }

  def deleteCandidate(
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      includeDeprecated: Boolean
  ): Option[Boolean] = data match {
    case TagNotFound(_)         => Some(true)
    case resource: IndexingData =>
      deleteCandidateResource(resource, resourceSchemas, resourceTypes, includeDeprecated)
  }

  private def deleteCandidateResource(
      resource: IndexingData,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      includeDeprecated: Boolean
  ): Option[Boolean] = if (containsSchema(resource, resourceSchemas) && containsTypes(resource, resourceTypes))
    Some(resource.deprecated && !includeDeprecated)
  else if (containsSchema(resource, resourceSchemas))
    Some(true)
  else
    None

  /**
    * Deletes the current resource named graph (and all its triples)
    */
  def delete(): Task[SparqlWriteQuery] =
    namedGraph(data.id).map(SparqlWriteQuery.drop)

  /**
    * Generates an Sparql replace query with all the triples to be added to the resource named graph
    */
  def index(resource: IndexingData, includeMetadata: Boolean): Task[Option[SparqlWriteQuery]] =
    for {
      triples    <- IO.fromEither(toTriples(resource, includeMetadata))
      namedGraph <- namedGraph(resource.id)
    } yield Option.when(triples.value.trim.nonEmpty)(SparqlWriteQuery.replace(namedGraph, triples))

  /**
    * Checks if the current resource contains some of the schemas passed as ''resourceSchemas''
    */
  private def containsSchema(resource: IndexingData, resourceSchemas: Set[Iri]): Boolean =
    resourceSchemas.isEmpty || resourceSchemas.contains(resource.schema.iri)

  /**
    * Checks if the current resource contains some of the types passed as ''resourceTypes''
    */
  private def containsTypes(resource: IndexingData, resourceTypes: Set[Iri]): Boolean =
    resourceTypes.isEmpty || resourceTypes.intersect(resource.types).nonEmpty

  private def toTriples(resource: IndexingData, includeMetadata: Boolean): Either[RdfError, NTriples] = {
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
    * Converts the resource retrieved from an event exchange to [[BlazegraphIndexingStreamEntry]]. It generates an
    * [[IndexingData]] for blazegraph indexing
    */
  def fromEventExchange(
      exchangedValue: EventExchangeResult
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): Task[BlazegraphIndexingStreamEntry] =
    ViewData(exchangedValue).map(BlazegraphIndexingStreamEntry(_))

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
