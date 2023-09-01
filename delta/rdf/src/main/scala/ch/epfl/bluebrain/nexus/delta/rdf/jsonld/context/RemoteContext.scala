package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Defines a remote context used for the Json-LD operations
  */
trait RemoteContext extends Product with Serializable {

  /**
    * The identifier of this context
    */
  def iri: Iri

  /**
    * The value of this context
    */
  def value: ContextValue

}

object RemoteContext {

  /**
    * A statically defined context
    */
  final case class StaticContext(iri: Iri, value: ContextValue) extends RemoteContext

}
