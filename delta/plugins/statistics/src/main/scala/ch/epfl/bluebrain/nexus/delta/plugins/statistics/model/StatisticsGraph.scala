package ch.epfl.bluebrain.nexus.delta.plugins.statistics.model

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.Statistics.{name, toPaths}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsGraph.{Edge, Node}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

import scala.annotation.nowarn

/**
  * Statistics graph with nodes and edges with their counts.
  *
  * @param nodes the nodes
  * @param edges the edges
  */
final case class StatisticsGraph(nodes: Seq[Node], edges: Seq[Edge])

object StatisticsGraph {

  /**
    * The node information.
    *
    * @param id    the node identifier
    * @param name  the node name
    * @param count the number of times this node occurs
    */
  final case class Node(id: Iri, name: String, count: Long)

  /**
    * The edge information.
    *
    * @param source the source node for this edge
    * @param target the destination node for this edge
    * @param count  the number of times this edge occurs
    * @param path   the edge sequence of path
    */
  final case class Edge(source: Iri, target: Iri, count: Long, path: Seq[EdgePath])

  /**
    * An edge path
    *
    * @param id   the edge path identifier
    * @param name the edge path name
    */
  final case class EdgePath(id: Iri, name: String)

  val empty: StatisticsGraph = StatisticsGraph(Seq.empty, Seq.empty)

  @nowarn("cat=unused")
  implicit private val relationshipsEncoder: Encoder.AsObject[StatisticsGraph] = {
    implicit val cfg: Configuration                          =
      Configuration.default.copy(transformMemberNames = {
        case "id"  => keywords.id
        case other => s"_$other"
      })
    implicit val nodeEncoder: Encoder.AsObject[Node]         = deriveConfiguredEncoder
    implicit val edgePathEncoder: Encoder.AsObject[EdgePath] = deriveConfiguredEncoder
    implicit val edgeEncoder: Encoder.AsObject[Edge]         = deriveConfiguredEncoder
    deriveConfiguredEncoder[StatisticsGraph]
  }
  implicit val relationshipsJsonLdEncoder: JsonLdEncoder[StatisticsGraph] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.relationships))

  implicit val relationshipsDecoderFromEsAggregations: Decoder[StatisticsGraph] = {

    def resourceType(hc: HCursor) =
      for {
        (tpe, count) <- termAgg(hc)
        node          = Node(tpe, name(tpe), count)
        paths        <- hc.downField("relationships").downField("filtered").downField("paths").get[Vector[Json]]("buckets")
        edgeSeq      <- paths.foldM(Vector.empty[Edge])((acc, json) => edges(tpe, json.hcursor).map(acc ++ _))
      } yield (node, edgeSeq)

    def edges(source: Iri, hc: HCursor) =
      for {
        paths   <- edgePaths(hc)
        types   <- hc.downField("pathTypes").get[Vector[Json]]("buckets")
        edgeSeq <- types.foldM(Vector.empty[Edge]) { (acc, json) =>
                     termAgg(json.hcursor).map { case (tpe, count) => acc :+ Edge(source, tpe, count, paths.toSeq) }
                   }
      } yield edgeSeq

    def edgePaths(hc: HCursor) =
      for {
        key   <- hc.get[String]("key")
        paths <- toPaths(key).map(_.map(iri => EdgePath(iri, name(iri)))).leftMap(DecodingFailure(_, hc.history))
      } yield paths

    def termAgg(hc: HCursor) =
      for {
        key   <- hc.get[Iri]("key")
        count <- hc.get[Long]("doc_count")
      } yield key -> count

    Decoder.instance { hc =>
      for {
        resourceTypes    <- hc.downField("aggregations").downField("resourceTypes").get[Vector[Json]]("buckets")
        resourceTypesAgg <- resourceTypes.foldM(StatisticsGraph.empty)((acc, json) =>
                              resourceType(json.hcursor).map { case (node, edges) =>
                                acc.copy(nodes = acc.nodes :+ node, edges = acc.edges ++ edges)
                              }
                            )
      } yield resourceTypesAgg

    }
  }

}
