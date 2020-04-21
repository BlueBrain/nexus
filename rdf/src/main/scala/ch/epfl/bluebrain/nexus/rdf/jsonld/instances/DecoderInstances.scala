package ch.epfl.bluebrain.nexus.rdf.jsonld.instances

import io.circe.Decoder

trait DecoderInstances {
  // In Json-LD arrays ([]) can be omitted if just one element is present on the Json array
  implicit private[jsonld] def jsonLDSetDecoder[A: Decoder]: Decoder[Set[A]] =
    Decoder.decodeSet[A] or Decoder.instance(_.value.as[A]).map(Set(_))

  // In Json-LD arrays ([]) can be omitted if just one element is present on the Json array
  implicit private[jsonld] def jsonLDSeqDecoder[A: Decoder]: Decoder[Seq[A]] =
    Decoder.decodeSeq[A] or Decoder.instance(_.value.as[A]).map(Seq(_))
}

object DecoderInstances extends DecoderInstances
