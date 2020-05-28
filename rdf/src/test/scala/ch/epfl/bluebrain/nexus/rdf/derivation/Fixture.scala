package ch.epfl.bluebrain.nexus.rdf.derivation

import java.util.UUID

import cats.data._
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.derivation.decoder.semiauto.deriveGraphDecoder
import ch.epfl.bluebrain.nexus.rdf.derivation.encoder.semiauto.deriveGraphEncoder
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.{GraphDecoder, GraphEncoder, NonEmptyString}
import com.github.ghik.silencer.silent
import io.circe.Json
import io.circe.literal._
import io.circe.parser._

@silent
object Fixture {
  sealed trait View   extends Product with Serializable
  sealed trait Values extends Product with Serializable

  object Values {
    final case class StringValue(id: AbsoluteIri, nonEmpty: NonEmptyString, empty: String)     extends Values
    final case class ListValue(id: AbsoluteIri, nonEmpty: NonEmptyList[Int], empty: List[Int]) extends Values
    final case class SetValue(id: AbsoluteIri, nonEmpty: NonEmptySet[Int], empty: Set[Int])    extends Values
    implicit final val valuesEncoder: GraphEncoder[Values] = deriveGraphEncoder[Values]
    implicit final val valuesDecoder: GraphDecoder[Values] = deriveGraphDecoder[Values]
  }

  object View {

    final case class ElasticSearchView(
        id: AbsoluteIri,
        uuid: Option[UUID],
        @id(nxv"mapping") notAMapping: Json,
        resourceSchemas: Set[AbsoluteIri],
        resourceTypes: Set[AbsoluteIri],
        resourceTag: Option[String] = None,
        sourceAsText: Option[Boolean] = Some(true)
    ) extends View

    final case class AggregateElasticSearchView(
        id: AbsoluteIri,
        uuid: Option[UUID],
        views: List[ViewRef]
    ) extends View

    final case class ViewRef(project: String, viewId: AbsoluteIri)
    object ViewRef {
      implicit final val viewRefEncoder: GraphEncoder[ViewRef] = deriveGraphEncoder[ViewRef]
      implicit final val viewRefDecoder: GraphDecoder[ViewRef] = deriveGraphDecoder[ViewRef]
    }

    implicit final val viewEncoder: GraphEncoder[View] = deriveGraphEncoder[View]
    implicit final val viewDecoder: GraphDecoder[View] = deriveGraphDecoder[View]

    implicit final val jsonEncoder: GraphEncoder[Json] =
      GraphEncoder.graphEncodeString.contramap(_.noSpaces)

    implicit final val jsonDecoder: GraphDecoder[Json] =
      GraphDecoder.graphDecodeString.emap(str => parse(str).leftMap(_.getMessage()))
  }

  val mapping: Json =
    json"""
      {
        "properties": {
          "@type": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "@id": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_rev": {
            "type": "long",
            "copy_to": "_all_fields"
          },
          "_deprecated": {
            "type": "boolean",
            "copy_to": "_all_fields"
          },
          "_createdAt": {
            "type": "date",
            "copy_to": "_all_fields"
          },
          "_updatedAt": {
            "type": "date",
            "copy_to": "_all_fields"
          },
          "_createdBy": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_updatedBy": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_constrainedBy": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_project": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_self": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_incoming": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_outgoing": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_original_source": {
            "type": "text",
            "copy_to": "_all_fields"
          },
          "_bytes": {
            "type": "long",
            "copy_to": "_all_fields"
          },
          "_mediaType": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_location": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_filename": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_digest": {
            "type": "nested",
            "properties": {
              "_algorithm": {
                "type": "keyword",
                "copy_to": "_all_fields"
              },
              "_value": {
                "type": "keyword",
                "copy_to": "_all_fields"
              }
            }
          },
          "_storage": {
            "type": "nested",
            "properties": {
              "_rev": {
                "type": "long",
                "copy_to": "_all_fields"
              },
              "@id": {
                "type": "keyword",
                "copy_to": "_all_fields"
              }
            }
          },
          "_all_fields": {
            "type": "text"
          }
        },
        "dynamic": false
      }
      """
}
