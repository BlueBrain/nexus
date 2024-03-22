package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{DetectChange, Resources, ValidateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring

object ResourceWiring {

  def resourceLog(
      fetchContext: FetchContext,
      schemaLog: IO[SchemaLog],
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): IO[ResourceLog] = {
    val detectChange = DetectChange(false)
    val aclCheck     = AclCheck(AclWiring.acls(config, clock, xas))
    val resolvers    = ResolverWiring.resolvers(fetchContext, config, clock, xas)

    for {
      fetchSchema       <- schemaLog.map(FetchSchema(_))
      resourceResolution =
        ResourceResolution.schemaResource(aclCheck, resolvers, fetchSchema, excludeDeprecated = false)
      validate           = ValidateResource(resourceResolution)(RemoteContextResolution.never)
      resourceDef        = Resources.definition(validate, detectChange, clock)
    } yield ScopedEventLog(resourceDef, config, xas)
  }

}
