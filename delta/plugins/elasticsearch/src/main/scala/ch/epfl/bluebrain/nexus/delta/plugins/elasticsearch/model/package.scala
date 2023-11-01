package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CatsEffectsClasspathResourceUtils
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

package object model {

  /**
    * Type alias for a view specific resource.
    */
  type ViewResource = ResourceF[ElasticSearchView]

  /**
    * The fixed virtual schema of an ElasticSearchView.
    */
  final val schema: ResourceRef = Latest(schemas + "views.json")

  /**
    * ElasticSearch views contexts.
    */
  object contexts {
    val aggregations          = nxvContexts + "aggregations.json"
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

  // TODO can do this only once and inject them where they're need instead of memoizing?

  /**
    * Default elasticsearch mapping for a view
    */
  val defaultElasticsearchMapping: IO[JsonObject] = CatsEffectsClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/default-mapping.json")
    .logErrors("loading default elasticsearch mapping")

  /**
    * Default elasticsearch settings for a view
    */
  val defaultElasticsearchSettings: IO[JsonObject] = CatsEffectsClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/default-settings.json")
    .logErrors("loading default elasticsearch settings")

  val emptyResults: IO[Json] = CatsEffectsClasspathResourceUtils
    .ioJsonObjectContentOf("defaults/empty-results.json")
    .logErrors("loading empty elasticsearch results")
    .map(_.asJson)

  /** Mapping for the event metrics index */
  val metricsMapping: IO[JsonObject] = CatsEffectsClasspathResourceUtils
    .ioJsonObjectContentOf("metrics/metrics-mapping.json")
    .logErrors("loading metrics mapping")

  /** Settings for the event metrics index */
  val metricsSettings: IO[JsonObject] = CatsEffectsClasspathResourceUtils
    .ioJsonObjectContentOf("metrics/metrics-settings.json")
    .logErrors("loading metrics settings")
}
