package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure.KeyMissingFailure

/**
  * Enumeration of representations for resource references.
  */
sealed trait ArchiveResourceRepresentation extends Product with Serializable

object ArchiveResourceRepresentation {

  /**
    * Source representation of a resource.
    */
  final case object SourceJson extends ArchiveResourceRepresentation

  /**
    * Compacted JsonLD representation of a resource.
    */
  final case object CompactedJsonLd extends ArchiveResourceRepresentation

  /**
    * Expanded JsonLD representation of a resource.
    */
  final case object ExpandedJsonLd extends ArchiveResourceRepresentation

  /**
    * NTriples representation of a resource.
    */
  final case object NTriples extends ArchiveResourceRepresentation

  /**
    * Dot representation of a resource.
    */
  final case object Dot extends ArchiveResourceRepresentation

  implicit final val archiveResourceRepresentationJsonLdDecoder: JsonLdDecoder[ArchiveResourceRepresentation] =
    (cursor: ExpandedJsonLdCursor) =>
      for {
        sourceOpt    <- cursor.downField(nxv + "originalSource").get[Option[Boolean]]
        formatCursor  = cursor.downField(nxv + "format")
        formatStrOpt <- formatCursor.get[Option[String]]
        formatOpt    <- formatStrOpt match {
                          case Some("source")    => Right(Some(SourceJson))
                          case Some("compacted") => Right(Some(CompactedJsonLd))
                          case Some("expanded")  => Right(Some(ExpandedJsonLd))
                          case Some("n-triples") => Right(Some(NTriples))
                          case Some("dot")       => Right(Some(Dot))
                          case Some(value)       => Left(ParsingFailure("Format", value, formatCursor.history))
                          case None              => Right(None)
                        }
        value        <- (sourceOpt, formatOpt) match {
                          case (Some(true), None)   => Right(SourceJson)
                          case (Some(false), None)  => Right(CompactedJsonLd)
                          case (None, Some(format)) => Right(format)
                          case (None, None)         => Left(KeyMissingFailure("originalSource", cursor.history))
                          case (Some(_), Some(_))   =>
                            Left(
                              ParsingFailure(
                                "An archive resource reference cannot use both 'originalSource' and 'format' fields."
                              )
                            )
                        }
      } yield value
}
