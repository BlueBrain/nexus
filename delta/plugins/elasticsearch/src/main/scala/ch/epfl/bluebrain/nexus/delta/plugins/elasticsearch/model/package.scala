package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.UIO

package object model {

  /**
    * Type alias for a view specific resource.
    */
  type ViewResource = ResourceF[ElasticSearchView]

  /**
    * Type alias for a indexing view specific resource.
    */
  type IndexingViewResource = ResourceF[IndexingElasticSearchView]

  /**
    * The fixed virtual schema of an ElasticSearchView.
    */
  final val schema: ResourceRef = Latest(schemas + "views.json")

  /**
    * ElasticSearch views contexts.
    */
  object contexts {
    val elasticsearch         = nxvContexts + "elasticsearch.json"
    val elasticsearchMetadata = nxvContexts + "elasticsearch-metadata.json"
    val elasticsearchIndexing = nxvContexts + "elasticsearch-indexing.json"
    val indexingMetadata      = nxvContexts + "indexing-metadata.json"
    val searchMetadata        = nxvContexts + "search-metadata.json"
  }

  object permissions {
    val write: Permission = Permission.unsafe("views/write")
    val read: Permission  = Permissions.resources.read
    val query: Permission = Permission.unsafe("views/query")
  }

  /**
    * The id for the default elasticsearch view
    */
  final val defaultViewId = nxv + "defaultElasticSearchIndex"

  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit private val logger: Logger = Logger("ElasticSearchPlugin")

  /**
    * Default elasticsearch mapping for a view
    */
  val defaultElasticsearchMapping: UIO[JsonObject] = ClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/default-mapping.json")
    .logAndDiscardErrors("loading default elasticsearch mapping")
    .memoize

  /**
    * Default elasticsearch settings for a view
    */
  val defaultElasticsearchSettings: UIO[JsonObject] = ClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/default-settings.json")
    .logAndDiscardErrors("loading default elasticsearch settings")
    .memoize

  val emptyResults: UIO[Json] = ClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/empty-results.json")
    .logAndDiscardErrors("loading empty elasticsearch results")
    .map(_.asJson)
    .memoize
}
