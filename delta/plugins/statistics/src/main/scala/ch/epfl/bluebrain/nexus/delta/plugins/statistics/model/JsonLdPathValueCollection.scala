package ch.epfl.bluebrain.nexus.delta.plugins.statistics.model

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPath.{ArrayPathEntry, ObjectPathEntry, RootPath}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValue.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection.{JsonLdProperties, JsonLdRelationships}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

/**
  * All the paths of a JSON-LD document
  *
  * @param properties    the properties (nested entries in the current document)
  * @param relationships the relationships (@id values pointing to external JSON-LD documents)
  */
final case class JsonLdPathValueCollection private[JsonLdPathValueCollection] (
    properties: JsonLdProperties,
    relationships: JsonLdRelationships
)

object JsonLdPathValueCollection {
  final case class JsonLdProperties(values: Seq[JsonLdPathValue]) { self =>
    def ++(that: JsonLdProperties): JsonLdProperties =
      JsonLdProperties(values ++ that.values)

    def +(that: JsonLdPathValue): JsonLdProperties =
      JsonLdProperties(values :+ that)

    def relationshipCandidates: Map[Iri, JsonLdPathValue] =
      values.map(v => v.metadata -> v).collect { case (Metadata(Some(id), _), pathValue) => id -> pathValue }.toMap
  }

  object JsonLdProperties {
    val empty: JsonLdProperties                                     = JsonLdProperties(Seq.empty)
    implicit val jsonLdPropertiesEncoder: Encoder[JsonLdProperties] = Encoder.instance(_.values.asJson)

    def fromExpanded(value: ExpandedJsonLd): JsonLdProperties = {

      def innerEntry(acc: JsonLdProperties, parent: JsonLdPath, json: Json): JsonLdProperties =
        json.arrayOrObject(
          acc,
          {
            case json +: Seq() => acc ++ innerEntry(JsonLdProperties.empty, parent, json)
            case arr           =>
              val arrParent = parent.asPathEntry.fold(parent)(parent => ArrayPathEntry(parent.predicate, parent.parent))
              acc ++ JsonLdProperties(arr.flatMap(innerEntry(JsonLdProperties.empty, arrParent, _).values))
          },
          obj => innerObj(acc, parent, obj)
        )

      def innerObj(acc: JsonLdProperties, parent: JsonLdPath, obj: JsonObject): JsonLdProperties = {
        val metadata = obj.asJson.as[Metadata].getOrElse(Metadata.empty)
        val value    = obj(keywords.value).getOrElse(Json.Null)
        val newAcc   = parent match {
          case JsonLdPath.RootPath                => acc
          case parent: JsonLdPath.JsonLdPathEntry => acc + JsonLdPathValue(value, parent, metadata)
        }

        obj.toVector
          .filterNot { case (k, _) => jsonLdKeywords.contains(k) }
          .foldLeft(newAcc) { case (acc, (key, value)) =>
            Iri.absolute(key).fold(_ => acc, predicate => innerEntry(acc, ObjectPathEntry(predicate, parent), value))
          }
      }

      innerEntry(JsonLdProperties.empty, RootPath, value.json)
    }
  }

  final case class JsonLdRelationships(values: Seq[JsonLdPathValue])

  object JsonLdRelationships {
    val empty: JsonLdRelationships                                        = JsonLdRelationships(Seq.empty)
    implicit val jsonLdRelationshipsEncoder: Encoder[JsonLdRelationships] = Encoder.instance(_.values.asJson)

  }

  private val jsonLdKeywords = Set(keywords.tpe, keywords.id, keywords.list, keywords.context, keywords.value)

  implicit val jsonLdPathValueCollectionEncoder: Encoder.AsObject[JsonLdPathValueCollection] =
    deriveEncoder[JsonLdPathValueCollection]
}
