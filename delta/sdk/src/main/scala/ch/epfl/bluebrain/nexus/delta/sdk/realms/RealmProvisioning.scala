package ch.epfl.bluebrain.nexus.delta.sdk.realms

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.RealmAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

/**
  * Provision the different realms provided in the configuration
  */
trait RealmProvisioning

object RealmProvisioning extends RealmProvisioning {

  private val logger = Logger[RealmProvisioning]

  def apply(
      realms: Realms,
      config: RealmsProvisioningConfig,
      serviceAccount: ServiceAccount
  ): IO[RealmProvisioning.type] =
    if (config.enabled) {
      implicit val serviceAccountSubject: Subject = serviceAccount.subject
      for {
        _ <- logger.info(s"Realm provisioning is active. Creating ${config.realms.size} realms...")
        _ <- config.realms.toList.traverse { case (label, fields) =>
               realms.create(label, fields).recoverWith {
                 case r: RealmAlreadyExists => logger.debug(r)(s"Realm '$label' already exists")
                 case e                     => logger.error(e)(s"Realm '$label' could not be created: '${e.getMessage}'")
               }
             }
        _ <- logger.info(s"Provisioning ${config.realms.size} realms is completed")
      } yield RealmProvisioning
    } else logger.info(s"Realm provisioning is inactive.").as(RealmProvisioning)

}
