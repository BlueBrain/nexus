package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import io.circe.Json

/**
  * Base trait for JSON-LD implementation. This specific implementation is entity centric, having always one root id
  */
trait JsonLd extends Product with Serializable {

  /**
    * The Circe Json Document representation of this JSON-LD
    */
  def json: Json

  /**
    * The top most @id value on the Json-LD Document
    */
  def rootId: IriOrBNode

  /**
    * Checks if the current [[JsonLd]] is empty
    */
  def isEmpty: Boolean

  /**
    * Checks if the current [[JsonLd]] is not empty
    */
  def nonEmpty: Boolean = !isEmpty
}
