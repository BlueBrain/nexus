package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.BlazegraphSupervision.BlazegraphNamespaces
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

trait BlazegraphSupervision {
  def get: IO[BlazegraphNamespaces]
}

object BlazegraphSupervision {

  final case class BlazegraphNamespaces(assigned: Map[ViewRef, Long], unassigned: Map[String, Long]) {
    def +(view: ViewRef, count: Long): BlazegraphNamespaces     = copy(assigned = assigned + (view -> count))
    def +(namespace: String, count: Long): BlazegraphNamespaces = copy(unassigned = unassigned + (namespace -> count))
  }

  object BlazegraphNamespaces {
    val empty: BlazegraphNamespaces = BlazegraphNamespaces(Map.empty, Map.empty)

    implicit final val blazegraphNamespacesEncoder: Encoder[BlazegraphNamespaces] = Encoder.AsObject.instance { value =>
      val assigned = value.assigned.toVector.sortBy(_._1.toString).map { case (view, count) =>
        Json.obj("project" := view.project, "view" := view.viewId, "count" := count)
      }

      val unassigned = value.unassigned.toVector.sortBy(_._1).map { case (namespace, count) =>
        Json.obj("namespace" := namespace, "count" := count)
      }

      JsonObject("assigned" := Json.arr(assigned: _*), "unassigned" := Json.arr(unassigned: _*))
    }
  }

  def apply(client: BlazegraphClient, viewsByNamespace: ViewByNamespace): BlazegraphSupervision =
    new BlazegraphSupervision {
      override def get: IO[BlazegraphNamespaces] = {
        for {
          namespaces       <- client.listNamespaces
          viewsByNamespace <- viewsByNamespace.get
          result           <- namespaces.foldLeftM(BlazegraphNamespaces.empty) { case (acc, namespace) =>
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
