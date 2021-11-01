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
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.Json
import io.circe.syntax._
import monix.bio.Task

//TODO remove when composite views are migrated to pipes
final case class CompositeIndexingStreamEntry(
    resource: IndexingData
)(implicit cr: RemoteContextResolution) {

  private val ctx: ContextValue =
    ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  /**
    * Deletes or indexes the current resource into ElasticSearch as a Document depending on the passed filters
    */
  def deleteOrIndex(
      idx: IndexLabel,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      sourceAsText: Boolean
  ): Task[Option[ElasticSearchBulk]] = {
    if (resource.deprecated && !includeDeprecated) delete(idx).map(Some.apply)
    else index(idx, includeMetadata, sourceAsText)
  }

  /**
    * Deletes the current resource Document
    */
  def delete(idx: IndexLabel): Task[ElasticSearchBulk] =
    Task.pure(ElasticSearchBulk.Delete(idx, resource.id.toString))

  /**
    * Generates an ElasticSearch Bulk Index query with the Document to be added to the index ''idx''
    */
  def index(
      idx: IndexLabel,
      includeMetadata: Boolean,
      sourceAsText: Boolean
  ): Task[Option[ElasticSearchBulk]] =
    index(idx, includeMetadata, sourceAsText, ctx)

  /**
    * Generates an ElasticSearch Bulk Index query with the Document to be added to the index ''idx''
    */
  def index(
      idx: IndexLabel,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      context: ContextValue
  ): Task[Option[ElasticSearchBulk]] =
    toDocument(includeMetadata, sourceAsText, context).map { doc =>
      Option.when(!doc.isEmpty())(ElasticSearchBulk.Index(idx, resource.id.toString, doc))
    }

  private def toDocument(
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

}

object CompositeIndexingStreamEntry {

  implicit private[indexing] val api: JsonLdApi = JsonLdJavaApi.lenient

}
