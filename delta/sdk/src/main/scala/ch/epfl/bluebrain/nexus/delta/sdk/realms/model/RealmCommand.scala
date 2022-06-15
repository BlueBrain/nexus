package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Name, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * Enumeration of Realm command types.
  */
sealed trait RealmCommand extends Product with Serializable {

  /**
    * @return
    *   the id of the realm
    */
  def label: Label

  /**
    * @return
    *   the subject that intends to evaluate this command
    */
  def subject: Subject
}

object RealmCommand {

  /**
    * An intent to create a new realm.
    *
    * @param label
    *   the label of the realm
    * @param name
    *   the name of the realm
    * @param openIdConfig
    *   the address of the openid configuration
    * @param logo
    *   an optional address for a logo
    * @param acceptedAudiences
    *   the optional set of audiences of this realm. JWT with `aud` which do not match this field will be rejected
    * @param subject
    *   the subject that intends to evaluate this command
    */
  final case class CreateRealm(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to update or un-deprecate an existing realm.
    *
    * @param label
    *   the label of the realm
    * @param rev
    *   the expected current revision of the resource
    * @param name
    *   the new name of the realm
    * @param openIdConfig
    *   the new address of the openid configuration
    * @param logo
    *   an optional new address for a logo
    * @param acceptedAudiences
    *   the optional set of audiences of this realm. JWT with `aud` which do not match this field will be rejected
    * @param subject
    *   the subject that intends to evaluate this command
    */
  final case class UpdateRealm(
      label: Label,
      rev: Int,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to deprecate a realm. Realm deprecation implies users will not be able to authorize requests using
    * tokens issued by the underlying provider.
    *
    * @param label
    *   the label of the realm
    * @param rev
    *   the expected current revision of the resource
    * @param subject
    *   the subject that intends to evaluate this command
    */
  final case class DeprecateRealm(
      label: Label,
      rev: Int,
      subject: Subject
  ) extends RealmCommand
}
