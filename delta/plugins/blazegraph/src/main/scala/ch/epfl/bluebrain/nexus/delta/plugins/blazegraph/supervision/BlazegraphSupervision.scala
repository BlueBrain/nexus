package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.BlazegraphSupervision.BlazegraphNamespaceTriples
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

/**
  * Gives supervision information for the underlying Blazegraph instance
  */
trait BlazegraphSupervision {
  def get: IO[BlazegraphNamespaceTriples]
}

object BlazegraphSupervision {

  /**
    * Returns the number of triples
    * @param total
    *   the total number of triples in the blazegraph instances
    * @param assigned
    *   the triples per Blazegraph views
    * @param unassigned
    *   the triples for namespaces which can not be associated to a Blazegraph view
    */
  final case class BlazegraphNamespaceTriples(
      total: Long,
      assigned: Map[ViewRef, Long],
      unassigned: Map[String, Long]
  ) {
    def +(view: ViewRef, count: Long): BlazegraphNamespaceTriples     =
      copy(total = total + count, assigned = assigned + (view -> count))
    def +(namespace: String, count: Long): BlazegraphNamespaceTriples =
      copy(total = total + count, unassigned = unassigned + (namespace -> count))
  }

  object BlazegraphNamespaceTriples {
    val empty: BlazegraphNamespaceTriples = BlazegraphNamespaceTriples(0L, Map.empty, Map.empty)

    implicit final val blazegraphNamespacesEncoder: Encoder[BlazegraphNamespaceTriples] = Encoder.AsObject.instance {
      value =>
        val assigned = value.assigned.toVector.sortBy(_._1.toString).map { case (view, count) =>
          Json.obj("project" := view.project, "view" := view.viewId, "count" := count)
        }

        val unassigned = value.unassigned.toVector.sortBy(_._1).map { case (namespace, count) =>
          Json.obj("namespace" := namespace, "count" := count)
        }

        JsonObject(
          "total"      := value.total,
          "assigned"   := Json.arr(assigned: _*),
          "unassigned" := Json.arr(unassigned: _*)
        )
    }
  }

  def apply(client: BlazegraphClient, viewsByNamespace: ViewByNamespace): BlazegraphSupervision =
    new BlazegraphSupervision {
      override def get: IO[BlazegraphNamespaceTriples] = {
        for {
          namespaces       <- client.listNamespaces
          viewsByNamespace <- viewsByNamespace.get
          result           <- namespaces.foldLeftM(BlazegraphNamespaceTriples.empty) { case (acc, namespace) =>
                                client.count(namespace).map { count =>
                                  viewsByNamespace.get(namespace) match {
                                    case Some(view) => acc + (view, count)
                                    case None       => acc + (namespace, count)
                                  }
                                }
                              }
        } yield result
      }
    }

}
