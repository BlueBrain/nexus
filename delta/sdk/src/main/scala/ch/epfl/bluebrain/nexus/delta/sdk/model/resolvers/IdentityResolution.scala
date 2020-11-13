package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity

/**
  * Enumeration of identity resolutions for a resolver
  */
sealed trait IdentityResolution

object IdentityResolution {

  /**
    * The resolution will use the identities of the caller at the moment of the resolution
    */
  final case object UseCurrentCaller extends IdentityResolution

  /**
    * The resolution will rely on the provided entities
    * @param value the identities
    */
  final case class ProvidedIdentities(value: Set[Identity]) extends IdentityResolution

}
