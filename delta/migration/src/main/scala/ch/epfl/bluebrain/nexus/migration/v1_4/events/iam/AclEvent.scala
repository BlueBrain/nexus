package ch.epfl.bluebrain.nexus.migration.v1_4.events.iam

import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent

import java.time.Instant

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends ToMigrateEvent {

  /**
    * @return the target path for the ACL
    */
  def path: AclAddress

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
    * @param path    the target path for the ACL
    * @param acl     the ACL replaced, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclReplaced(
      path: AclAddress,
      acl: AccessControlList,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL append.
    *
    * @param path    the target path for the ACL
    * @param acl     the ACL appended, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclAppended(
      path: AclAddress,
      acl: AccessControlList,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL subtraction.
    *
    * @param path    the target path for the ACL
    * @param acl     the ACL subtracted, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclSubtracted(
      path: AclAddress,
      acl: AccessControlList,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL deletion.
    *
    * @param path    the target path for the ACL
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclDeleted(
      path: AclAddress,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

}
