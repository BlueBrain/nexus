package ai.senscience.nexus.delta.plugins.graph.analytics.model

import ai.senscience.nexus.delta.plugins.graph.analytics.model.JsonLdDocument.Reference
import ai.senscience.nexus.delta.plugins.graph.analytics.model.JsonLdEntry.ObjectEntry
import cats.Monoid
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}

/**
  * Describes the shape of a JSON-LD document
  * @param properties
  *   the nested entries in the documents
  * @param relationships
  *   resolved references to other resources/files in the same project
  * @param references
  *   references to other documents
  */
final case class JsonLdDocument(
    properties: Set[JsonLdEntry],
    relationships: Set[ObjectEntry],
    references: Set[Reference],
    referenceIds: Set[Iri]
)

object JsonLdDocument {

  final case class Reference(entry: ObjectEntry, found: Boolean)

  object Reference {

    implicit val nodeReferenceEncoder: Encoder.AsObject[Reference] = Encoder.AsObject.instance { reference =>
      reference.entry.asJsonObject.add("found", reference.found.asJson)
    }
  }

  val empty: JsonLdDocument = JsonLdDocument(Set.empty, Set.empty, Set.empty, Set.empty)

  def singleEntry(entry: JsonLdEntry): JsonLdDocument = JsonLdDocument(Set(entry), Set.empty, Set.empty, Set.empty)

  /**
    * Creates a [[JsonLdDocument]] from a expanded JSON-LD
    * @param expanded
    *   the expanded JSON-LD to analyze
    * @param findRelationships
    *   the function to apply to resolve resources/files
    */
  def fromExpanded(
      expanded: ExpandedJsonLd,
      findRelationships: Set[Iri] => IO[Map[Iri, Set[Iri]]]
  ): IO[JsonLdDocument] = {

    def innerEntry(json: Json, path: Vector[Iri], isInArray: Boolean): JsonLdDocument = {

      def innerArray(array: Vector[Json]) =
        array
          .foldMap { json =>
            innerEntry(json, path, isInArray = true)
          }

      def innerObject(obj: JsonObject) = {
        // Literal node
        JsonLdEntry.literal(obj, path, isInArray).map(JsonLdDocument.singleEntry).getOrElse {
          // If this is not a literal node (i.e. there is no '@value' field), we have a node object
          // We drop the root level
          val objectEntry = Option.when(path.nonEmpty) {
            val id    = obj(keywords.id).flatMap(_.as[Iri].toOption)
            val types = obj(keywords.tpe).flatMap(_.as[Set[Iri]].toOption)
            ObjectEntry(
              id,
              types,
              path,
              isInArray
            )
          }

          val innerDocs = obj.toVector.foldMap {
            case (key, _) if jsonLdKeywords.contains(key) =>
              JsonLdDocument.empty
            case (key, value)                             =>
              Iri
                .reference(key)
                .fold(
                  _ => JsonLdDocument.empty,
                  k => innerEntry(value, path.appended(k), isInArray)
                )
          }

          objectEntry.fold(innerDocs) { o =>
            JsonLdDocument(
              properties = Set(o),
              relationships = Set.empty,
              references = o.id.as(Reference(o, found = false)).toSet,
              referenceIds = o.id.toSet
            ) |+| innerDocs
          }
        }
      }

      json.arrayOrObject(
        JsonLdDocument.empty,
        {
          case Vector(single) => innerEntry(single, path, isInArray)
          case array          => innerArray(array)
        },
        innerObject
      )
    }

    // Resolves the reference but not yet the relationships
    val firstPass = innerEntry(expanded.json, Vector.empty, isInArray = false)

    findRelationships(firstPass.referenceIds).map { resolved =>
      firstPass.references.foldLeft(empty.copy(properties = firstPass.properties)) { case (acc, reference) =>
        val typesOpt = reference.entry.id.flatMap { id =>
          resolved.get(id)
        }

        val append = typesOpt.fold {
          JsonLdDocument.empty.copy(references = Set(reference))
        } { types =>
          JsonLdDocument.empty.copy(
            relationships = Set(reference.entry.copy(types = Some(types))),
            references = Set(reference.copy(found = true))
          )
        }

        acc |+| append
      }
    }

  }

  implicit val jsonLdDocumentMonoid: Monoid[JsonLdDocument] = new Monoid[JsonLdDocument] {
    override def empty: JsonLdDocument = JsonLdDocument.empty

    override def combine(x: JsonLdDocument, y: JsonLdDocument): JsonLdDocument = JsonLdDocument(
      x.properties ++ y.properties,
      x.relationships ++ y.relationships,
      x.references ++ y.references,
      x.referenceIds ++ y.referenceIds
    )
  }

  implicit val jsonLdDocumentEncoder: Encoder[JsonLdDocument] = {
    implicit val config: Configuration = Configuration.default
    deriveConfiguredEncoder[JsonLdDocument].mapJsonObject(_.remove("referenceIds"))
  }

  private val jsonLdKeywords = Set(keywords.tpe, keywords.id, keywords.list, keywords.context, keywords.value)

}
