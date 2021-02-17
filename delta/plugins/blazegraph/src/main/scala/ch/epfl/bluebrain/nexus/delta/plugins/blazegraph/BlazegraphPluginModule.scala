package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import izumi.distage.model.definition.ModuleDef

/**
  * Blazegraph plugin wiring
  */
object BlazegraphPluginModule extends ModuleDef {

  make[BlazegraphScopeInitialization]
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

}
