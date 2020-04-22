package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, EmptyNullOr}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final private[jsonld] case class ContextWrapper(`@context`: EmptyNullOr[Context])
private[jsonld] object ContextWrapper {
  implicit val contextWrapperDecoder: Decoder[ContextWrapper] = deriveDecoder[ContextWrapper]

  def fromParent(parent: EmptyNullOr[Context]): Decoder[ContextWrapper] = {
    implicit val contextDecoder: Decoder[Context]                 = Decoder.decodeJson.emap(ContextParser(_, parent))
    implicit val nonContextDecoder: Decoder[EmptyNullOr[Context]] = EmptyNullOr.decodeNoneOrNullValue
    Decoder.forProduct1(keyword.context)(ContextWrapper.apply)
  }
}
