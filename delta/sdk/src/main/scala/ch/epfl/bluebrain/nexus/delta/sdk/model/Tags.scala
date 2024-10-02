package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json, JsonObject}

final case class Tags(value: Map[UserTag, Int]) extends AnyVal {
  def contains(tag: UserTag): Boolean = value.contains(tag)
  def +(tag: (UserTag, Int)): Tags    = Tags(value + tag)
  def ++(tags: Tags): Tags            = Tags(value ++ tags.value)
  def -(tag: UserTag): Tags           = Tags(value - tag)
  def tags: List[UserTag]             = value.keys.toList
}

object Tags {

  val empty: Tags = new Tags(Map.empty)

  def apply(value: (UserTag, Int)): Tags = Tags(Map(value))

  def apply(first: (UserTag, Int), values: (UserTag, Int)*): Tags = Tags(Map(first) ++ values)

  def apply(maybeTag: Option[UserTag], rev: Int): Tags = maybeTag.fold(empty)(t => apply(t -> rev))

  implicit val tagsDecoder: Decoder[Tags]          =
    Decoder.decodeMap[UserTag, Int].map(Tags(_))
  implicit val tagsEncoder: Encoder.AsObject[Tags] =
    Encoder.encodeMap[UserTag, Int].contramapObject(_.value)

  implicit final val tagsJsonLdEncoder: JsonLdEncoder[Tags] = {
    implicit val tagsEncoder: Encoder.AsObject[Tags] = Encoder.AsObject.instance { tags =>
      JsonObject.apply(
        "tags" -> Json.fromValues(tags.value.map { case (tag, rev) =>
          Json.obj("tag" -> tag.asJson, "rev" -> rev.asJson)
        })
      )
    }
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.tags))
  }

  implicit val versionHttpResponseFields: HttpResponseFields[Tags] = HttpResponseFields.defaultOk
}
