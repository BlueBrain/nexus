package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingStreamEntry._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, rdfs, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeResult
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData.{IndexingResource, TagNotFound}
import io.circe.Json
import io.circe.syntax._
import monix.bio.Task
import org.apache.jena.graph.Node

final case class ElasticSearchIndexingStreamEntry(
    data: IndexingData
)(implicit cr: RemoteContextResolution) {

  def writeOrNone(index: IndexLabel, view: IndexingElasticSearchView): Task[Option[ElasticSearchBulk]] = writeOrNone(
    index,
    view.resourceSchemas,
    view.resourceTypes,
    view.includeMetadata,
    view.includeDeprecated,
    view.sourceAsText
  )

  def writeOrNone(
      index: IndexLabel,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      sourceAsText: Boolean
  ): Task[Option[ElasticSearchBulk]] = data match {
    case TagNotFound(id)            => delete(id, index).map(Some(_))
    case resource: IndexingResource =>
      if (containsSchema(resource, resourceSchemas) && containsTypes(resource, resourceTypes))
        deleteOrIndex(
          resource,
          index,
          includeMetadata,
          includeDeprecated,
          sourceAsText
        )
      else if (containsSchema(resource, resourceSchemas))
        delete(resource.id, index).map(Some.apply)
      else
        Task.none
  }

  private val ctx: ContextValue =
    ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  /**
    * Deletes or indexes the current resource into ElasticSearch as a Document depending on the passed filters
    */
  def deleteOrIndex(
      resource: IndexingResource,
      idx: IndexLabel,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      sourceAsText: Boolean
  ): Task[Option[ElasticSearchBulk]] = {
    if (resource.deprecated && !includeDeprecated) delete(resource.id, idx).map(Some.apply)
    else index(resource, idx, includeMetadata, sourceAsText)
  }

  /**
    * Deletes the current resource Document
    */
  def delete(id: Iri, idx: IndexLabel): Task[ElasticSearchBulk] =
    Task.pure(ElasticSearchBulk.Delete(idx, id.toString))

  /**
    * Generates an ElasticSearch Bulk Index query with the Document to be added to the index ''idx''
    */
  def index(
      resource: IndexingResource,
      idx: IndexLabel,
      includeMetadata: Boolean,
      sourceAsText: Boolean
  ): Task[Option[ElasticSearchBulk]] =
    index(resource, idx, includeMetadata, sourceAsText, ctx)

  /**
    * Generates an ElasticSearch Bulk Index query with the Document to be added to the index ''idx''
    */
  def index(
      resource: IndexingResource,
      idx: IndexLabel,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      context: ContextValue
  ): Task[Option[ElasticSearchBulk]] =
    toDocument(resource, includeMetadata, sourceAsText, context).map { doc =>
      Option.when(!doc.isEmpty())(ElasticSearchBulk.Index(idx, resource.id.toString, doc))
    }

  /**
    * Checks if the current resource contains some of the schemas passed as ''resourceSchemas''
    */
  def containsSchema(resource: IndexingResource, resourceSchemas: Set[Iri]): Boolean =
    resourceSchemas.isEmpty || resourceSchemas.contains(resource.schema.iri)

  /**
    * Checks if the current resource contains some of the types passed as ''resourceTypes''
    */
  def containsTypes[A](resource: IndexingResource, resourceTypes: Set[Iri]): Boolean =
    resourceTypes.isEmpty || resourceTypes.intersect(resource.types).nonEmpty

  private def toDocument(
      resource: IndexingResource,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      context: ContextValue
  ): Task[Json] = {
    val predGraph = resource.graph
    val metaGraph = resource.metadataGraph
    val graph     = if (includeMetadata) predGraph ++ metaGraph else predGraph
    if (sourceAsText)
      graph
        .add(nxv.originalSource.iri, resource.source.noSpaces)
        .toCompactedJsonLd(context)
        .map(_.obj.asJson)
    else if (resource.source.isEmpty())
      graph
        .toCompactedJsonLd(context)
        .map(_.obj.asJson)
    else
      (graph -- graph.rootTypesGraph)
        .replaceRootNode(BNode.random) // This is done to get rid of the @id in order to avoid overriding the source @id
        .toCompactedJsonLd(context)
        .map(ld => mergeJsonLd(resource.source, ld.json).removeAllKeys(keywords.context))
  }

  private def mergeJsonLd(a: Json, b: Json): Json =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else a deepMerge b

}

object ElasticSearchIndexingStreamEntry {

  implicit private[indexing] val api: JsonLdApi = JsonLdJavaApi.lenient

  private val graphPredicates: Set[Node] =
    Set(skos.prefLabel, rdf.tpe, rdfs.label, Vocabulary.schema.name).map(predicate)

  /**
    * Converts the resource retrieved from an event exchange to [[ElasticSearchIndexingStreamEntry]]. It generates an
    * [[IndexingResource]] out of the relevant parts of the resource for elasticsearch indexing
    */
  def fromEventExchange(
      exchangeResult: EventExchangeResult
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): Task[ElasticSearchIndexingStreamEntry] =
    IndexingData(exchangeResult, graphPredicates).map(ElasticSearchIndexingStreamEntry(_))
}
