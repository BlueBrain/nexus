package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.PipeCatsEffect
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotDecodePipeConfigErr
import shapeless.Typeable

/**
  * Contract for definition for pipes. PipeDefs are used to construct [[PipeCatsEffect]] instances to be used when
  * materializing runnable projections. They are indexed by their label in a registry such they can be looked up given a
  * [[PipeRef]]. Provided with a configuration, pipe definitions can produce [[PipeCatsEffect]] instances.
  */
trait PipeDefCats {

  /**
    * The type of the [[PipeCatsEffect]] that this definition produces.
    */
  type PipeType <: PipeCatsEffect

  /**
    * The required configuration type for producing a [[PipeCatsEffect]] of this type.
    */
  type Config

  /**
    * @return
    *   the Typeable instance for the required [[PipeCatsEffect]] configuration
    */
  def configType: Typeable[Config]

  /**
    * @return
    *   a json-ld decoder for the [[PipeCatsEffect]] configuration
    */
  def configDecoder: JsonLdDecoder[Config]

  /**
    * @return
    *   the unique reference for a pipe of this type
    */
  def ref: PipeRef

  /**
    * Produces a [[PipeCatsEffect]] instance given an expected configuration.
    *
    * @param config
    *   the configuration for the [[PipeDefCats]]
    */
  def withConfig(config: Config): PipeType

  /**
    * Attempts to construct a corresponding [[PipeCatsEffect]] instance by decoding the required configuration from a
    * json-ld configuration.
    *
    * @param jsonLd
    *   the source configuration in the json-ld format
    */
  def withJsonLdConfig(jsonLd: ExpandedJsonLd): Either[CouldNotDecodePipeConfigErr, PipeType] =
    configDecoder(jsonLd)
      .map(c => withConfig(c))
      .leftMap(e => CouldNotDecodePipeConfigErr(jsonLd, configType.describe, ref, e.reason))

}
