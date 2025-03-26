package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient

/**
  * Defines the operations to create and destroy the namespaces of a composite view
  */
trait CompositeSpaces {

  /**
    * Creates all spaces for the given view
    */
  def init(view: ActiveViewDef): IO[Unit]

  /**
    * Destroys all spaces for the given view
    */
  def destroyAll(view: ActiveViewDef): IO[Unit]

  /**
    * Destroys space for the projection of the given view
    */
  def destroyProjection(view: ActiveViewDef, projection: CompositeViewProjection): IO[Unit]

}

object CompositeSpaces {

  private val logger = Logger[CompositeSpaces]

  def apply(
      prefix: String,
      esClient: ElasticSearchClient,
      sparqlClient: SparqlClient
  ): CompositeSpaces = new CompositeSpaces {
    override def init(view: ActiveViewDef): IO[Unit] = {
      val common       = commonNamespace(view.uuid, view.indexingRev, prefix)
      val createCommon = sparqlClient.createNamespace(common).void
      val result       = view.value.projections.foldLeft[IO[Unit]](createCommon) {
        case (acc, e: ElasticSearchProjection) =>
          val index = projectionIndex(e, view.uuid, prefix)
          acc >> esClient.createIndex(index, Some(e.mapping), e.settings).void
        case (acc, s: SparqlProjection)        =>
          val namespace = projectionNamespace(s, view.uuid, prefix)
          acc >> sparqlClient.createNamespace(namespace).void
      }
      logger.debug(s"Creating namespaces and indices for composite view ${view.ref}") >> result
    }

    override def destroyAll(view: ActiveViewDef): IO[Unit] = {
      val common       = commonNamespace(view.uuid, view.indexingRev, prefix)
      val deleteCommon = sparqlClient.deleteNamespace(common).void
      val result       = view.value.projections.foldLeft[IO[Unit]](deleteCommon) { case (acc, p) =>
        acc >> destroyProjection(view, p)
      }
      logger.debug(s"Deleting namespaces and indices for composite view ${view.ref}") >> result
    }

    override def destroyProjection(view: ActiveViewDef, projection: CompositeViewProjection): IO[Unit] =
      logger.debug(s"Deleting namespace/index for projection ${projection.id} of composite view ${view.ref}") >> {
        projection match {
          case e: ElasticSearchProjection =>
            val index = projectionIndex(e, view.uuid, prefix)
            esClient.deleteIndex(index).void
          case s: SparqlProjection        =>
            val namespace = projectionNamespace(s, view.uuid, prefix)
            sparqlClient.deleteNamespace(namespace).void
        }
      }
  }
}
