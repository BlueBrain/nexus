package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel

import java.util.UUID

package object indexing {

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def commonNamespace(uuid: UUID, rev: Int, prefix: String): String =
    s"${prefix}_${uuid}_$rev"

  /**
    * The Blazegraph namespace for the passed projection
    *
    * @param projection
    *   the views' Blazegraph projection
    * @param uuid
    *   the view uuid
    * @param rev
    *   the view revision
    * @param prefix
    *   the namespace prefix
    */
  def projectionNamespace(projection: SparqlProjection, uuid: UUID, rev: Int, prefix: String): String =
    s"${prefix}_${uuid}_${projection.uuid}_$rev"

  /**
    * The Elasticsearch index for the passed projection
    *
    * @param projection
    *   the views' Elasticsearch projection
    * @param uuid
    *   the view uuid
    * @param rev
    *   the view revision
    * @param prefix
    *   the index prefix
    */
  def projectionIndex(projection: ElasticSearchProjection, uuid: UUID, rev: Int, prefix: String): IndexLabel = {
    val completePrefix = projection.indexGroup.fold(prefix) { i => s"${prefix}_$i" }
    IndexLabel.unsafe(s"${completePrefix}_${uuid}_${projection.uuid}_$rev")
  }
}
