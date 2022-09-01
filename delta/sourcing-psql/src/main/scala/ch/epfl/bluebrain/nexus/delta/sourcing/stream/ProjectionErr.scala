package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd

/**
  * Enumeration of recoverable errors for projection/stream definitions and compositions.
  */
sealed trait ProjectionErr extends Product with Serializable {

  /**
    * @return
    *   a human readable reason for which the error occurred
    */
  def reason: String
}

object ProjectionErr {

  /**
    * Applying a json-ld configuration to a source definition would typically yield a materialized source. This error
    * signals the inability to decode a provided json-ld configuration as per the expectation of the source definition.
    * @param cfg
    *   the json-ld configuration that was provided
    * @param to
    *   the target type expected by the source definition
    * @param source
    *   the source reference
    * @param message
    *   a human readable message describing the decoding issue
    */
  final case class CouldNotDecodeSourceConfigErr(
      cfg: ExpandedJsonLd,
      to: String,
      source: SourceRef,
      message: String
  ) extends ProjectionErr {
    override def reason: String =
      s"Unable to decode config '${cfg.json.noSpaces}' to '$to' for source '${source.label.value}' because '$message'"
  }

  /**
    * Two sources can be merged if the their out types are identical. This error signals a failed attempt to merge two
    * sources with incompatible out types.
    * @param self
    *   the source to be merged with that source
    * @param that
    *   the source being merged with self
    */
  final case class SourceOutMatchErr(self: Source, that: Source) extends ProjectionErr {
    override def reason: String =
      s"Unable to match Out type '${self.outType.describe}' of source '${self.name}' to the Out type '${that.outType.describe}' of source '${that.name}'"
  }

  /**
    * A source can be chained with a Pipe if the source Out type matches the Pipe In type. This error signals a failed
    * attempt to chain a source to a pipe due to incompatible types.
    * @param self
    *   the source to be chained with the pipe
    * @param that
    *   the pipe to attach to the source
    */
  final case class SourceOutPipeInMatchErr(self: Source, that: Pipe) extends ProjectionErr {
    override def reason: String =
      s"Unable to match Out type '${self.outType.describe}' of source '${self.name}' to the In type '${that.inType.describe}' of pipe '${that.name}'"
  }

  /**
    * Applying a json-ld configuration to a pipe definition would typically yield a materialized pipe. This error
    * signals the inability to decode a provided json-ld configuration as per the expectation of the pipe definition.
    * @param cfg
    *   the json-ld configuration that was provided
    * @param to
    *   the target type expected by the pipe definition
    * @param pipe
    *   the pipe reference
    * @param message
    *   a human readable message describing the decoding issue
    */
  final case class CouldNotDecodePipeConfigErr(
      cfg: ExpandedJsonLd,
      to: String,
      pipe: PipeRef,
      message: String
  ) extends ProjectionErr {
    override def reason: String =
      s"Unable to decode config '${cfg.json.noSpaces}' to '$to' for pipe '${pipe.label.value}' because '$message'"
  }

  /**
    * Two pipes can be merged if the Out type of the first is identical to the In type of the second. This error signals
    * a failed attempt to merge two pipes with incompatible Out and In types.
    * @param self
    *   the pipe to be merged with that pipe
    * @param that
    *   the pipe being merged with self
    */
  final case class PipeInOutMatchErr(self: Pipe, that: Pipe) extends ProjectionErr {
    override def reason: String =
      s"Unable to match Out type '${self.outType.describe}' of pipe '${self.name}' to the In type '${that.inType.describe}' of pipe '${that.name}'"
  }

  /**
    * A source definition can be looked up in the [[ReferenceRegistry]] using a reference. This error signals a failed
    * lookup attempt.
    *
    * @param ref
    *   the source reference
    */
  final case class CouldNotFindSourceErr(ref: SourceRef) extends ProjectionErr {
    override def reason: String = s"Unable to find source reference '${ref.label.value}'"
  }

  /**
    * A pipe definition can be looked up in the [[ReferenceRegistry]] using a reference. This error signals a failed
    * lookup attempt.
    *
    * @param ref
    *   the pipe reference
    */
  final case class CouldNotFindPipeErr(ref: PipeRef) extends ProjectionErr {
    override def reason: String = s"Unable to find pipe reference '${ref.label.value}'"
  }

  /**
    * A pipe definition can be looked up in the [[ReferenceRegistry]] using a reference. This error signals a failed
    * lookup attempt for a pipe with a specific type.
    *
    * @param ref
    *   the pipe reference
    */
  final case class CouldNotFindTypedPipeErr(ref: PipeRef, tpe: String) extends ProjectionErr {
    override def reason: String = s"Unable to find pipe reference '${ref.label.value}' of expected type '$tpe'"
  }

  /**
    * Pipes in the terminal position require an Out type of Unit. This error signals that a [[PipeChain]] could not be
    * compiled because the requirement is not met.
    * @param self
    *   the pipe that was expected to have an Out type of Unit
    */
  final case class PipeChainOutNotUnitErr(self: Pipe) extends ProjectionErr {
    override def reason: String = s"The pipe ${self.name} does not have its terminal output type Unit"
  }

}
