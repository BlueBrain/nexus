package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends Product with Serializable {

  /**
    * @return the target location for the ACL
    */
  def target: Target

  /**
    * @return the revision that this event generated
    */
  def rev: Long

  /**
    * @return the instant when this event was created
    */
  def instant: Instant

  /**
    * @return the subject which created this event
    */
  def subject: Subject

}

object AclEvent {

  /**
    * A witness to ACL replace.
    *
    * @param target  the target location for the ACL
    * @param acl     the ACL replaced, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclReplaced(
      target: Target,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL append.
    *
    * @param target  the target location for the ACL
    * @param acl     the ACL appended, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclAppended(
      target: Target,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL subtraction.
    *
    * @param target  the target location for the ACL
    * @param acl     the ACL subtracted, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclSubtracted(
      target: Target,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL deletion.
    *
    * @param target  the target location for the ACL
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclDeleted(
      target: Target,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

}
