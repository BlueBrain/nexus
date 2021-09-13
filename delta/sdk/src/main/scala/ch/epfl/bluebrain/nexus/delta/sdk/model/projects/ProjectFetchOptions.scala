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
    * Not deleted project
    */
  final case object NotDeleted extends ProjectFetchOptions

  /**
    * Project that does not have a quota restriction for resources
    */
  final case object VerifyQuotaResources extends ProjectFetchOptions

  /**
    * Project that does not have a quota restriction for events
    */
  final case object VerifyQuotaEvents extends ProjectFetchOptions

  val notDeprecatedOrDeleted: Set[ProjectFetchOptions] =
    Set(NotDeprecated, NotDeleted)

  val notDeprecatedOrDeletedWithResourceQuotas: Set[ProjectFetchOptions] =
    Set(NotDeprecated, NotDeleted, VerifyQuotaResources)

  val notDeprecatedOrDeletedWithEventQuotas: Set[ProjectFetchOptions] =
    Set(NotDeprecated, NotDeleted, VerifyQuotaEvents)

  val notDeprecatedOrDeletedWithQuotas: Set[ProjectFetchOptions] =
    Set(NotDeprecated, NotDeleted, VerifyQuotaResources, VerifyQuotaEvents)

  val allQuotas: Set[ProjectFetchOptions] =
    Set(VerifyQuotaResources, VerifyQuotaEvents)
}
