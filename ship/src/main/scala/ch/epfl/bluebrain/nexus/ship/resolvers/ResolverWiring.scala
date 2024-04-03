package ch.epfl.bluebrain.nexus.ship.resolvers

import cats.effect.IO
import cats.effect.kernel.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers, ResolversImpl, ValidatePriority}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.FailingUUID

object ResolverWiring {

  def resolvers(fetchContext: FetchContext, config: EventLogConfig, clock: Clock[IO], xas: Transactors)(implicit
      jsonLdApi: JsonLdApi
  ): Resolvers = {
    implicit val uuidF: UUIDF = FailingUUID

    val alwaysValidatePriority: ValidatePriority = (_, _, _) => IO.unit

    ResolversImpl(
      fetchContext,
      // We rely on the parsed values and not on the original value
      ResolverContextResolution.never,
      alwaysValidatePriority,
      config,
      xas,
      clock
    )
  }

}
