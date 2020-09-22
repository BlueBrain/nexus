package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}

/**
  * Enumeration of Realm command types.
  */
sealed trait RealmCommand extends Product with Serializable {

  /**
    * @return the id of the realm
    */
  def label: Label

  /**
    * @return the subject that intends to evaluate this command
    */
  def subject: Subject
}

object RealmCommand {

  /**
    * An intent to create a new realm.
    *
    * @param label        the label of the realm
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional address for a logo
    * @param subject      the subject that intends to evaluate this command
    */
  final case class CreateRealm(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to update or un-deprecate an existing realm.
    *
    * @param label        the label of the realm
    * @param rev          the expected current revision of the resource
    * @param name         the new name of the realm
    * @param openIdConfig the new address of the openid configuration
    * @param logo         an optional new address for a logo
    * @param subject      the subject that intends to evaluate this command
    */
  final case class UpdateRealm(
      label: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to deprecate a realm. Realm deprecation implies users will not be able to authorize requests using
    * tokens issued by the underlying provider.
    *
    * @param label   the label of the realm
    * @param rev     the expected current revision of the resource
    * @param subject the subject that intends to evaluate this command
    */
  final case class DeprecateRealm(
      label: Label,
      rev: Long,
      subject: Subject
  ) extends RealmCommand
}
