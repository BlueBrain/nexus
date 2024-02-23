package ch.epfl.bluebrain.nexus.ship.organizations

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{Organizations, OrganizationsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.ship.organizations.OrganizationProvider.logger

final class OrganizationProvider(orgs: Organizations, serviceAccount: ServiceAccount) {

  implicit val subject: Identity.Subject = serviceAccount.subject

  def create(values: Map[Label, String]): IO[Unit] =
    values.toList.traverse { case (label, description) =>
      orgs.create(label, Option.when(description.nonEmpty)(description))(subject).recoverWith {
        case _: OrganizationAlreadyExists => logger.info(s"Organization '$label' already exists.")
      }
    }.void
}

object OrganizationProvider {
  private val logger = Logger[OrganizationProvider]

  def apply(config: EventLogConfig, serviceAccount: ServiceAccount, xas: Transactors, clock: Clock[IO])(implicit
      uuidf: UUIDF
  ): OrganizationProvider = {
    val orgs = OrganizationsImpl(ScopeInitializer.noop, config, xas, clock)
    new OrganizationProvider(orgs, serviceAccount)
  }
}
