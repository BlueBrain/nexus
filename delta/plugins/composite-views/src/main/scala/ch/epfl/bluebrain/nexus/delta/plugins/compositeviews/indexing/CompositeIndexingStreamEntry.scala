package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingStreamEntry._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.{IndexingData, TagNotFound}
import io.circe.Json
import io.circe.syntax._
import monix.bio.Task

final case class CompositeIndexingStreamEntry(
    data: ViewData
)(implicit cr: RemoteContextResolution) {

  def writeOrNone(
      index: IndexLabel,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      sourceAsText: Boolean,
      context: ContextValue = ctx,
      includeContext: Boolean
  ): Task[Option[ElasticSearchBulk]] = data match {
    case TagNotFound(id)        => delete(id, index).map(Some(_))
    case resource: IndexingData =>
      if (containsSchema(resource, resourceSchemas) && containsTypes(resource, resourceTypes))
        deleteOrIndex(
          resource,
          index,
          includeMetadata,
          includeDeprecated,
          sourceAsText,
          context,
          includeContext
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
      resource: IndexingData,
      idx: IndexLabel,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      sourceAsText: Boolean,
      context: ContextValue,
      includeContext: Boolean
  ): Task[Option[ElasticSearchBulk]] = {
    if (resource.deprecated && !includeDeprecated) delete(resource.id, idx).map(Some.apply)
    else index(resource, idx, includeMetadata, sourceAsText, context, includeContext)
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
      resource: IndexingData,
      idx: IndexLabel,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      context: ContextValue = ctx,
      includeContext: Boolean
  ): Task[Option[ElasticSearchBulk]] =
    toDocument(resource, includeMetadata, sourceAsText, context, includeContext).map { doc =>
      Option.when(!doc.isEmpty())(ElasticSearchBulk.Index(idx, resource.id.toString, doc))
    }

  /**
    * Checks if the current resource contains some of the schemas passed as ''resourceSchemas''
    */
  def containsSchema(resource: IndexingData, resourceSchemas: Set[Iri]): Boolean =
    resourceSchemas.isEmpty || resourceSchemas.contains(resource.schema.iri)

  /**
    * Checks if the current resource contains some of the types passed as ''resourceTypes''
    */
  def containsTypes[A](resource: IndexingData, resourceTypes: Set[Iri]): Boolean =
    resourceTypes.isEmpty || resourceTypes.intersect(resource.types).nonEmpty

  private def toDocument(
      resource: IndexingData,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      context: ContextValue,
      includeContext: Boolean
  ): Task[Json] = {
    val predGraph = resource.graph
    val metaGraph = resource.metadataGraph
    val graph     = if (includeMetadata) predGraph ++ metaGraph else predGraph

    print(graph
      .toCompactedJsonLd(context))

    if (sourceAsText)
      graph
        .add(nxv.originalSource.iri, resource.source.noSpaces)
        .toCompactedJsonLd(context)
        .map(_.obj.asJson)
    else if (resource.source.isEmpty()) {
      if (!includeContext) graph
        .toCompactedJsonLd(context)
        .map(_.obj.asJson)
      else {
       val compacted = graph
          .toCompactedJsonLd(context)

        // TODO: do not exclude context object from document
        //val ctx = compacted.map(_.ctx.asJson)
        val obj = compacted.map(_.obj.asJson)
        obj
      }
    } else
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

object CompositeIndexingStreamEntry {

  implicit private[indexing] val api: JsonLdApi = JsonLdJavaApi.lenient

}
