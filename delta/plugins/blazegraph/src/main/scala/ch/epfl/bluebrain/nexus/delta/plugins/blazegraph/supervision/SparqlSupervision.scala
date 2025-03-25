package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.SparqlSupervision.SparqlNamespaceTriples
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

/**
  * Gives supervision information for the underlying SPARQL instance
  */
trait SparqlSupervision {
  def get: IO[SparqlNamespaceTriples]
}

object SparqlSupervision {

  /**
    * Returns the number of triples
    * @param total
    *   the total number of triples in the SPARQL instances
    * @param assigned
    *   the triples per SPARQL views
    * @param unassigned
    *   the triples for namespaces which can not be associated to a SPARQL view
    */
  final case class SparqlNamespaceTriples(
      total: Long,
      assigned: Map[ViewRef, Long],
      unassigned: Map[String, Long]
  ) {
    def +(view: ViewRef, count: Long): SparqlNamespaceTriples     =
      copy(total = total + count, assigned = assigned + (view -> count))
    def +(namespace: String, count: Long): SparqlNamespaceTriples =
      copy(total = total + count, unassigned = unassigned + (namespace -> count))
  }

  object SparqlNamespaceTriples {
    val empty: SparqlNamespaceTriples = SparqlNamespaceTriples(0L, Map.empty, Map.empty)

    implicit final val sparqlNamespacesEncoder: Encoder[SparqlNamespaceTriples] = Encoder.AsObject.instance { value =>
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

  def apply(client: SparqlClient, viewsByNamespace: ViewByNamespace): SparqlSupervision =
    new SparqlSupervision {
      override def get: IO[SparqlNamespaceTriples] = {
        for {
          namespaces       <- client.listNamespaces
          viewsByNamespace <- viewsByNamespace.get
          result           <- namespaces.foldLeftM(SparqlNamespaceTriples.empty) { case (acc, namespace) =>
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
