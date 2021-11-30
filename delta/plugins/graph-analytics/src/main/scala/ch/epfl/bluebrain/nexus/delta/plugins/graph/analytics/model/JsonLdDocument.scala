package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model

import cats.Monoid
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument.Reference
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdEntry.ObjectEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}
import monix.bio.{IO, UIO}

import scala.annotation.nowarn

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
    references: Set[Reference]
)

object JsonLdDocument {

  final case class Reference(entry: ObjectEntry, found: Boolean)

  object Reference {

    implicit val nodeReferenceEncoder: Encoder.AsObject[Reference] = Encoder.AsObject.instance { reference =>
      reference.entry.asJsonObject.add("found", reference.found.asJson)
    }
  }

  val empty: JsonLdDocument = JsonLdDocument(Set.empty, Set.empty, Set.empty)

  def singleEntry(entry: JsonLdEntry): JsonLdDocument = JsonLdDocument(Set(entry), Set.empty, Set.empty)

  /**
    * Creates a [[JsonLdDocument]] from a expanded JSON-LD
    * @param expanded
    *   the expanded JSON-LD to analyze
    * @param findRelationship
    *   the function to apply to resolve resources/files
    */
  def fromExpanded(
      expanded: ExpandedJsonLd,
      findRelationship: Iri => UIO[Option[Set[Iri]]]
  ): UIO[JsonLdDocument] = {

    def innerEntry(json: Json, path: Vector[Iri], isInArray: Boolean): UIO[JsonLdDocument] = {

      def innerArray(array: Vector[Json]) =
        array
          .foldMapM { json =>
            innerEntry(json, path, isInArray = true)
          }

      def innerObject(obj: JsonObject) = {
        IO.fromOption(
          // Literal node
          JsonLdEntry.literal(obj, path, isInArray).map(JsonLdDocument.singleEntry)
        ).onErrorFallbackTo {
          // If this is not a literal node (i.e. there is no '@value' field), we have a node object

          //We drop the root level
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

          for {
            // Attempts to resolve the @id to get the types
            relationship <- objectEntry.flatMap(_.id).fold(UIO.none[Set[Iri]])(findRelationship)
            // Analyze the object fields
            innerDocs    <- obj.toVector.foldMapM {
                              case (key, _) if jsonLdKeywords.contains(key) =>
                                UIO.pure(JsonLdDocument.empty)
                              case (key, value)                             =>
                                Iri
                                  .absolute(key)
                                  .fold(
                                    _ => UIO.pure(JsonLdDocument.empty),
                                    k => innerEntry(value, path.appended(k), isInArray)
                                  )
                            }
          } yield {
            objectEntry.fold(innerDocs) { o =>
              JsonLdDocument(
                properties = Set(o),
                relationships = relationship.map { types =>
                  o.copy(types = Some(types))
                }.toSet,
                references = o.id.as(Reference(o, relationship.isDefined)).toSet
              ) |+| innerDocs
            }
          }
        }
      }

      json.arrayOrObject(
        UIO.pure(JsonLdDocument.empty),
        {
          case Vector(single) => innerEntry(single, path, isInArray)
          case array          => innerArray(array)
        },
        innerObject
      )
    }

    innerEntry(expanded.json, Vector.empty, isInArray = false)
  }

  implicit val jsonLdDocumentMonoid: Monoid[JsonLdDocument] = new Monoid[JsonLdDocument] {
    override def empty: JsonLdDocument = JsonLdDocument.empty

    override def combine(x: JsonLdDocument, y: JsonLdDocument): JsonLdDocument = JsonLdDocument(
      x.properties ++ y.properties,
      x.relationships ++ y.relationships,
      x.references ++ y.references
    )
  }

  @nowarn("cat=unused")
  implicit val jsonLdDocumentEncoder: Encoder[JsonLdDocument] = {
    implicit val config: Configuration = Configuration.default
    deriveConfiguredEncoder[JsonLdDocument]
  }

  private val jsonLdKeywords = Set(keywords.tpe, keywords.id, keywords.list, keywords.context, keywords.value)

}
