package ch.epfl.bluebrain.nexus.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.auth.Identity.Subject

/**
  * Enumeration of Realm command types.
  */
sealed trait RealmCommand extends Product with Serializable {

  /**
    * @return the id of the realm
    */
  def id: RealmLabel

  /**
    * @return the subject that intends to evaluate this command
    */
  def subject: Subject
}

object RealmCommand {

  /**
    * An intent to create a new realm.
    *
    * @param id           the label of the realm
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional address for a logo
    * @param subject      the subject that intends to evaluate this command
    */
  final case class CreateRealm(
      id: RealmLabel,
      name: String,
      openIdConfig: Uri,
      logo: Option[Uri],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to update or un-deprecate an existing realm.
    *
    * @param id           the label of the realm
    * @param rev          the expected current revision of the resource
    * @param name         the new name of the realm
    * @param openIdConfig the new address of the openid configuration
    * @param logo         an optional new address for a logo
    * @param subject      the subject that intends to evaluate this command
    */
  final case class UpdateRealm(
      id: RealmLabel,
      rev: Long,
      name: String,
      openIdConfig: Uri,
      logo: Option[Uri],
      subject: Subject
  ) extends RealmCommand

  /**
    * An intent to deprecate a realm. Realm deprecation implies users will not be able to authorize requests using
    * tokens issued by the underlying provider.
    *
    * @param id      the label of the realm
    * @param rev     the expected current revision of the resource
    * @param subject the subject that intends to evaluate this command
    */
  final case class DeprecateRealm(
      id: RealmLabel,
      rev: Long,
      subject: Subject
  ) extends RealmCommand
}
