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
    * Project that does not have a quota restriction for resources
    */
  final case object VerifyQuotaResources extends ProjectFetchOptions

  /**
    * Project that does not have a quota restriction for events
    */
  final case object VerifyQuotaEvents extends ProjectFetchOptions

  val notDeprecated: Set[ProjectFetchOptions]                   = Set(NotDeprecated)
  val notDeprecatedWithResourceQuotas: Set[ProjectFetchOptions] = Set(NotDeprecated, VerifyQuotaResources)
  val notDeprecatedWithEventQuotas: Set[ProjectFetchOptions]    = Set(NotDeprecated, VerifyQuotaEvents)
  val notDeprecatedWithQuotas: Set[ProjectFetchOptions]         = Set(NotDeprecated, VerifyQuotaResources, VerifyQuotaEvents)
  val allQuotas: Set[ProjectFetchOptions]                       = Set(VerifyQuotaResources, VerifyQuotaEvents)
}
