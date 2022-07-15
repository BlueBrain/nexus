package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue

/**
  * Result of a MultiResolution
  */
final case class MultiResolutionResult[R](report: R, value: ReferenceExchangeValue[_])
