package ch.epfl.bluebrain.nexus.delta.sdk.resources

import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json}

final case class NexusSource(value: Json) extends AnyVal

object NexusSource {

  implicit val nexusSourceDecoder: Decoder[NexusSource] = Decoder.decodeJsonObject.emap { obj =>
    val underscoreFields = obj.keys.filter(_.startsWith("_"))
    Either.cond(
      underscoreFields.isEmpty,
      NexusSource(obj.asJson),
      s"Field(s) starting with _ found in payload: ${underscoreFields.mkString(", ")}"
    )
  }
}
