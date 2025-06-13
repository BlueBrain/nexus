package ai.senscience.nexus.delta.plugins.graph.analytics.model

import ai.senscience.nexus.delta.plugins.graph.analytics.GraphAnalytics.{name, toPaths}
import ai.senscience.nexus.delta.plugins.graph.analytics.contexts
import ai.senscience.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.*
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.*

import scala.annotation.tailrec

/**
  * Each property with its counts.
  *
  * @param metadata
  *   the property information (names and counts)
  * @param properties
  *   the nested properties of the current property
  */
final case class PropertiesStatistics(metadata: Metadata, properties: Seq[PropertiesStatistics])

object PropertiesStatistics {

  /**
    * The property information.
    *
    * @param id
    *   the property @id
    * @param name
    *   the property name
    * @param count
    *   the number of times this property appears
    */
  final case class Metadata(id: Iri, name: String, count: Long)

  implicit private lazy val propertiesEncoder: Encoder.AsObject[PropertiesStatistics] = {
    implicit val cfg: Configuration = Configuration.default.copy(transformMemberNames = {
      case "id"  => keywords.id
      case other => s"_$other"
    })

    implicit val metadataEncoder: Encoder.AsObject[Metadata] = deriveConfiguredEncoder

    Encoder.encodeJsonObject.contramapObject { case PropertiesStatistics(metadata, properties) =>
      metadata.asJsonObject.addIfNonEmpty("_properties", properties)
    }
  }

  implicit val propertiesJsonLdEncoder: JsonLdEncoder[PropertiesStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.properties))

  implicit val propertiesStatisticsHttpResponseFields: HttpResponseFields[PropertiesStatistics] =
    HttpResponseFields.defaultOk

  implicit def propertiesDecoderFromEsAggregations(tpe: Iri): Decoder[PropertiesStatistics] = {

    implicit val intOrdering: Ordering[Int] = Ordering.Int.reverse

    @tailrec
    def inner(
        initial: Metadata,
        rest: List[PathsList],
        acc: Map[List[Iri], PropertiesStatistics]
    ): PropertiesStatistics =
      rest match {
        case (paths @ _ :: parents, metadata) :: tail =>
          val childProperties   = acc.get(paths).map(_.properties).getOrElse(Seq.empty)
          val currentProperties = acc.get(parents).map(_.properties).getOrElse(Seq.empty)
          val pathStats         =
            PropertiesStatistics(metadata, currentProperties :+ PropertiesStatistics(metadata, childProperties))
          inner(initial, tail, acc - paths + (parents -> pathStats))
        case _                                        =>
          PropertiesStatistics(initial, acc.values.headOption.map(_.properties).getOrElse(Seq.empty))
      }

    Decoder.instance { hc =>
      for {
        count    <- hc.downField("hits").downField("total").get[Long]("value")
        paths    <- hc.downField("aggregations")
                      .downField("properties")
                      .downField("filtered")
                      .downField("paths")
                      .get[Vector[Json]]("buckets")
        pathsAgg <- paths.foldM(List.empty[PathsSeq]) { (acc, json) =>
                      val hc = json.hcursor
                      for {
                        key   <- hc.get[String]("key")
                        paths <- toPaths(key).leftMap(DecodingFailure(_, hc.history))
                        count <- hc.get[Long]("doc_count")
                        iri    = paths.last
                      } yield (paths.toSeq -> Metadata(iri, name(iri), count)) :: acc
                    }
        sorted    = pathsAgg.map { case (path, meta) => path.reverse.toList -> meta }.sortBy(_._1.size)
      } yield inner(Metadata(tpe, name(tpe), count), sorted, Map.empty)

    }
  }

  private type PathsSeq  = (Seq[Iri], Metadata)
  private type PathsList = (List[Iri], Metadata)

}
