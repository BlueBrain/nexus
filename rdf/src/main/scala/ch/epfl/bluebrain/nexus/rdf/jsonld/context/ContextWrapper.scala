package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.{NoneNullOr, keyword}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final private[jsonld] case class ContextWrapper(`@context`: NoneNullOr[Context])
private[jsonld] object ContextWrapper {
  implicit val contextWrapperDecoder: Decoder[ContextWrapper] = deriveDecoder[ContextWrapper]

  def fromParent(parent: NoneNullOr[Context]): Decoder[ContextWrapper] = {
    implicit val contextDecoder: Decoder[Context] = Decoder.decodeJson.emap(ContextParser(_, parent))
    implicit val nonContextDecoder: Decoder[NoneNullOr[Context]] = NoneNullOr.decodeNoneOrNullValue
    Decoder.forProduct1(keyword.context)(ContextWrapper.apply)
  }
}
