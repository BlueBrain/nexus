package ch.epfl.bluebrain.nexus.delta.sdk.model.identities

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

/**
  * A service account backed by a subject.
  *
  * @param subject
  *   the underlying service account subject
  */
final case class ServiceAccount(subject: Subject) extends AnyVal {

  /**
    * @return
    *   the collection of identities of this service account
    */
  def identities: Set[Identity] = Set(subject)

  /**
    * @return
    *   a [[Caller]] representation for this service account
    */
  def caller: Caller = Caller.unsafe(subject, identities)
}
