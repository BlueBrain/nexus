package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import izumi.distage.model.definition.ModuleDef

/**
  * ElasticSearch plugin wiring.
  */
object ElasticSearchPluginModule extends ModuleDef {

  make[ElasticSearchScopeInitialization]
  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]
}
