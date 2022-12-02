package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverEvent, ResolverState, ResolverValue}

object MigrationLogHelpers {

  // Resolver
  private val defaultResolverId = Vocabulary.nxv.defaultResolver

  private def setResolverDefaults(
      resolverName: Option[String],
      resolverDescription: Option[String]
  ): ResolverValue => ResolverValue = {
    case ipv: InProjectValue    => ipv.copy(name = resolverName, description = resolverDescription)
    case cpv: CrossProjectValue => cpv.copy(name = resolverName, description = resolverDescription)
  }

  /** Inject name and description into resolver events */
  def injectResolverDefaults(
      defaults: Defaults
  ): (ResolverEvent, Option[ResolverState]) => ResolverEvent = (e, _) =>
    e match {
      case r @ ResolverCreated(id, _, value, _, _, _, _) if id == defaultResolverId =>
        r.copy(value = setResolverDefaults(Some(defaults.name), Some(defaults.description))(value))
      case r @ ResolverUpdated(id, _, value, _, _, _, _) if id == defaultResolverId =>
        r.copy(value = setResolverDefaults(Some(defaults.name), Some(defaults.description))(value))
      case e                                                                        => e
    }

}
