package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.DecodingDerivationFailure
import magnolia.{CaseClass, SealedTrait}

private[decoder] object MagnoliaJsonLdDecoder {

  def combine[A](caseClass: CaseClass[JsonLdDecoder, A])(implicit config: Configuration): JsonLdDecoder[A] = {
    val keysMap = caseClass.parameters
      .filter { p => p.label != config.idPredicateName }
      .map { p =>
        p.label -> config.context
          .expand(p.label, useVocab = true)
          .toRight {
            val err = s"Label '${p.label}' could not be converted to Iri. Please provide a correct context"
            DecodingDerivationFailure(err)
          }
      }
      .toMap

    new JsonLdDecoder[A] {

      override def apply(cursor: ExpandedJsonLdCursor): Either[JsonLdDecoderError, A] =
        caseClass.constructMonadic {
          case p if p.label == config.idPredicateName => decodeOrDefault(p.typeclass, cursor, p.default)
          case p                                      =>
            keysMap(p.label).flatMap { predicate =>
              val nextCursor = cursor.downField(predicate)
              decodeOrDefault(p.typeclass, nextCursor, p.default)
            }
        }

      private def decodeOrDefault[B](dec: JsonLdDecoder[B], cursor: ExpandedJsonLdCursor, default: Option[B]) =
        default match {
          case Some(value) if !cursor.succeeded => Right(value)
          case _                                => dec(cursor)
        }
    }
  }

  def dispatch[A](sealedTrait: SealedTrait[JsonLdDecoder, A])(implicit config: Configuration): JsonLdDecoder[A] =
    new JsonLdDecoder[A] {

      override def apply(c: ExpandedJsonLdCursor): Either[JsonLdDecoderError, A] =
        c.getTypes match {
          case Right(types) =>
            sealedTrait.subtypes
              .find(st => types.exists(config.context.expand(st.typeName.short, useVocab = true).contains)) match {
              case Some(st) => st.typeclass.apply(c)
              case None     =>
                val err = s"Unable to find type discriminator for '${sealedTrait.typeName.short}'"
                Left(DecodingDerivationFailure(err))
            }
          case Left(err)    => Left(err)
        }
    }
}
