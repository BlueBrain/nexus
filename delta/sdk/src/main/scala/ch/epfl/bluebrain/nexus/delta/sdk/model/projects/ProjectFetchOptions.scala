package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

/**
  * Enumeration of all possible project fetch options
  */
sealed trait ProjectFetchOptions extends Product with Serializable

object ProjectFetchOptions {

  /**
    * Not deprecated project
    */
  final case object NotDeprecated extends ProjectFetchOptions

  /**
    * Project that does not have a quota restriction
    */
  final case object VerifyQuotaResources extends ProjectFetchOptions
}
