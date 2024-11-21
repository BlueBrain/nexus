package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction.Outcome
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclProvisioning.logger
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclBatchReplace
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount

/**
  * Provision the different acls provided in the file defined by the configuration
  */
final class AclProvisioning(acls: Acls, config: AclProvisioningConfig, serviceAccount: ServiceAccount)
    extends ProvisioningAction {

  override def run: IO[ProvisioningAction.Outcome] =
    if (config.enabled) {
      acls.isRootAclSet.flatMap {
        case true  =>
          logger.warn("Root acls are already set in this instance, skipping the provisioning...").as(Outcome.Skipped)
        case false =>
          config.path
            .traverse(FileUtils.loadJsonAs[AclBatchReplace])
            .flatMap {
              case Some(input) => loadAcls(input, acls, serviceAccount).as(Outcome.Success)
              case None        => logger.error("No acl provisioning file has been defined.").as(Outcome.Error)
            }
            .recoverWith { e =>
              logger.error(e)(s"Acl provisionning failed because of '${e.getMessage}'.").as(Outcome.Error)
            }
      }
    } else logger.info(s"Acl provisioning is inactive.").as(Outcome.Disabled)

  private def loadAcls(input: AclBatchReplace, acls: Acls, serviceAccount: ServiceAccount) = {
    logger.info(s"Provisioning ${input.acls.size} acl entries...") >>
      input.acls.traverse { acl =>
        acls.replace(acl, 0)(serviceAccount.subject).recoverWith { e =>
          logger.error(e)(s"Acl for address '${acl.address}' could not be set: '${e.getMessage}.")
        }
      }.void >> logger.info("Provisioning acls is completed.")
  }
}

object AclProvisioning {

  private val logger = Logger[AclProvisioning]
}
