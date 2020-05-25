package ch.epfl.bluebrain.nexus.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.auth.Identity
import ch.epfl.bluebrain.nexus.auth.Identity.Subject
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.utils.Codecs
import com.github.ghik.silencer.silent
import io.circe.Encoder
import io.circe.generic.extras.Configuration

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends Product with Serializable {

  /**
    * @return the target location for the ACL
    */
  def target: AclTarget

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
      target: AclTarget,
      acl: AccessControlList,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL append.
    *
    * @param target    the target location for the ACL
    * @param acl     the ACL appended, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclAppended(
      target: AclTarget,
      acl: AccessControlList,
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
      target: AclTarget,
      acl: AccessControlList,
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
      target: AclTarget,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  object JsonLd extends Codecs {
    import io.circe.generic.extras.semiauto._

    @silent // defined implicits are not recognized as being used
    implicit def aclEventEncoder(implicit httpConfig: HttpConfig): Encoder[AclEvent] = {
      implicit val config: Configuration = Configuration.default
        .withDiscriminator("@type")
        .copy(transformMemberNames = {
          case "rev"     => "_rev"
          case "instant" => "_instant"
          case "subject" => "_subject"
          case "path"    => "_path"
          case other     => other
        })
      implicit val arrayEncoder: Encoder[AccessControlList] = AccessControlList.aclArrayEncoder
      implicit val subjectEncoder: Encoder[Subject]         = Identity.subjectIdEncoder
      deriveConfiguredEncoder[AclEvent]
//        .mapJson { json =>
//          json
//            .addContext(iamCtxUri)
//            .addContext(resourceCtxUri)
//        }
    }
  }
}
