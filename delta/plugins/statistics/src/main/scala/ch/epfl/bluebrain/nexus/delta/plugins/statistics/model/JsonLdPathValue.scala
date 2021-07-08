package ch.epfl.bluebrain.nexus.delta.plugins.statistics.model

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPath.JsonLdPathEntry
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValue._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import cats.syntax.functor._
import io.circe.{Decoder, Encoder, Json, JsonObject}

/**
  * The enumeration of path values (@value, @id and @type)
  */
sealed trait JsonLdPathValue extends Product with Serializable {
  def parent: JsonLdPathEntry
  def metadata: Metadata
  def withMeta(metadata: Metadata): JsonLdPathValue = this match {
    case v: StringPathValue  => v.copy(metadata = metadata)
    case v: NumericPathValue => v.copy(metadata = metadata)
    case v: BooleanPathValue => v.copy(metadata = metadata)
    case v: EmptyPathValue   => v.copy(metadata = metadata)
  }
}

object JsonLdPathValue {

  final case class StringPathValue(parent: JsonLdPathEntry, metadata: Metadata)  extends JsonLdPathValue
  final case class NumericPathValue(parent: JsonLdPathEntry, metadata: Metadata) extends JsonLdPathValue
  final case class BooleanPathValue(parent: JsonLdPathEntry, metadata: Metadata) extends JsonLdPathValue
  final case class EmptyPathValue(parent: JsonLdPathEntry, metadata: Metadata)   extends JsonLdPathValue

  def apply(value: Json, parent: JsonLdPathEntry, metadata: Metadata): JsonLdPathValue =
    (value.asString.as(StringPathValue(parent, metadata)) orElse
      value.asNumber.as(NumericPathValue(parent, metadata)) orElse
      value.asBoolean.as(BooleanPathValue(parent, metadata))).getOrElse(EmptyPathValue(parent, metadata))

  implicit val jsonLdPathEncoder: Encoder.AsObject[JsonLdPathValue] =
    Encoder.encodeJsonObject.contramapObject[JsonLdPathValue] { p =>
      val obj = JsonObject(
        "path"      -> p.parent.predicates.mkString(" / ").asJson,
        "isInArray" -> p.parent.isInArray.asJson
      ) deepMerge p.metadata.asJsonObject
      p match {
        case _: BooleanPathValue => obj deepMerge JsonObject("dataType" -> "boolean".asJson)
        case _: NumericPathValue => obj deepMerge JsonObject("dataType" -> "numeric".asJson)
        case _: StringPathValue  => obj deepMerge JsonObject("dataType" -> "string".asJson)
        case _: EmptyPathValue   => obj
      }
    }

  /**
    * A Paths' metadata
    *
    * @param id    the path @id value
    * @param types the path @type values
    */
  final case class Metadata(id: Option[Iri], types: Set[Iri])

  object Metadata {
    val empty: Metadata = Metadata(None, Set.empty)

    implicit val metadataDecoder: Decoder[Metadata] =
      Decoder.instance { hc =>
        for {
          id    <- hc.get[Option[Iri]](keywords.id)
          types <- hc.get[Option[Set[Iri]]](keywords.tpe) orElse hc.get[Option[Iri]](keywords.tpe).map(_.map(Set(_)))
        } yield Metadata(id, types.getOrElse(Set.empty))
      }

    implicit val metadataEncoder: Encoder.AsObject[Metadata] =
      Encoder.encodeJsonObject.contramapObject { meta =>
        JsonObject.empty.addIfExists(keywords.id, meta.id).addIfNonEmpty(keywords.tpe, meta.types)
      }
  }
}
