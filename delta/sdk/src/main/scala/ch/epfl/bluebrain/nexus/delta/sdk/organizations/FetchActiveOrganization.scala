package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{Organization, OrganizationState}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateGet
import doobie.Get
import doobie.implicits._

object FetchActiveOrganization {

  implicit val getValue: Get[OrganizationState] = OrganizationState.serializer.getValue

  def apply(org: Label, xas: Transactors): IO[Organization] =
    GlobalStateGet[Label, OrganizationState](Organizations.entityType, org)
      .transact(xas.read)
      .flatMap {
        case None                    => IO.raiseError(OrganizationNotFound(org))
        case Some(o) if o.deprecated => IO.raiseError(OrganizationIsDeprecated(org))
        case Some(o)                 => IO.pure(o.toResource.value)
      }

}
