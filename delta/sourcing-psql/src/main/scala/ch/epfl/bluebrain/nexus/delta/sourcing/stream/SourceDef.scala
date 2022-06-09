package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotDecodeSourceConfigErr
import shapeless.Typeable

/**
  * Contract for definition of sources. SourceDefs are used to construct Source instances to be used when materializing
  * runnable projections. They are indexed by their label in a registry such they can be looked up given a
  * [[SourceRef]]. Provided with a configuration, source definitions can produce [[Source]] instances.
  */
trait SourceDef {

  /**
    * The type of the [[Source]] that this definition produces.
    */
  type SourceType <: Source

  /**
    * The required configuration type for producing a [[Source]].
    */
  type Config

  /**
    * @return
    *   the Typeable instance for the required [[Source]] configuration
    */
  def configType: Typeable[Config]

  /**
    * @return
    *   a json-ld decoder for the [[Source]] configuration
    */
  def configDecoder: JsonLdDecoder[Config]

  /**
    * @return
    *   the label that represents the specific source type
    */
  def label: Label

  /**
    * @return
    *   the unique reference for a source of this type
    */
  def reference: SourceRef = SourceRef(label)

  /**
    * Produces a [[Source]] instance given an expected configuration.
    *
    * @param config
    *   the configuration for the [[SourceDef]]
    * @param id
    *   a unique identifier for the resulting [[Source]]
    */
  def withConfig(config: Config, id: Iri): SourceType

  /**
    * Attempts to construct a corresponding [[Source]] instance by decoding the required configuration from a json-ld
    * configuration.
    *
    * @param jsonLd
    *   the source configuration in the json-ld format
    * @param id
    *   a unique identifier for the resulting [[Source]]
    */
  def withJsonLdConfig(jsonLd: ExpandedJsonLd, id: Iri): Either[CouldNotDecodeSourceConfigErr, SourceType] =
    configDecoder(jsonLd)
      .map(c => withConfig(c, id))
      .leftMap(e => CouldNotDecodeSourceConfigErr(jsonLd, configType.describe, reference, e.reason))

}
