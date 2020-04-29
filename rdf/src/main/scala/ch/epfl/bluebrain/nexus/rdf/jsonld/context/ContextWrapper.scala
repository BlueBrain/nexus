package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, EmptyNullOr, JsonLdOptions}
import com.github.ghik.silencer.silent
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final private[jsonld] case class ContextWrapper(`@context`: EmptyNullOr[Context])
private[jsonld] object ContextWrapper {
  implicit def contextWrapperDecoder(implicit @silent options: JsonLdOptions): Decoder[ContextWrapper] =
    deriveDecoder[ContextWrapper]

  def fromParent(parent: EmptyNullOr[Context])(implicit options: JsonLdOptions): Decoder[ContextWrapper] = {
    implicit val contextDecoder: Decoder[Context] =
      Decoder.decodeJson.emap(ContextParser(_, parent))
    Decoder.forProduct1(keyword.context)(ContextWrapper.apply)
  }
}
