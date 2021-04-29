package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue

/**
  * Result of a [[MultiResolution]]
  */
case class MultiResolutionResult[R](report: R, value: ReferenceExchangeValue[_])

object MultiResolutionResult {

  val context: ContextValue = ContextValue(contexts.resolvers)

}
