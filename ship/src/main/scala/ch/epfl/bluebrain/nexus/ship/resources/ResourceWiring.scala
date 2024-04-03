package ch.epfl.bluebrain.nexus.ship.resources

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{DetectChange, FetchResource, Resources, ValidateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring.alwaysAuthorize
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring

object ResourceWiring {

  def apply(
      fetchContext: FetchContext,
      fetchSchema: FetchSchema,
      remoteContext: RemoteContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): (ResourceLog, FetchResource) = {
    val detectChange       = DetectChange(false)
    val resolvers          = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    val resourceResolution =
      ResourceResolution.schemaResource(alwaysAuthorize, resolvers, fetchSchema, excludeDeprecated = false)
    val validate           = ValidateResource(resourceResolution)(remoteContext)
    val resourceDef        = Resources.definition(validate, detectChange, clock)

    val log = ScopedEventLog(resourceDef, config, xas)
    (log, FetchResource(log))
  }

}
