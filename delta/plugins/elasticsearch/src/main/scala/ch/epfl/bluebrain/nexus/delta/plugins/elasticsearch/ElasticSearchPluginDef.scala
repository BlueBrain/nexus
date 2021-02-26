package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class ElasticSearchPluginDef extends PluginDef {

  implicit private val classLoader = getClass.getClassLoader

  override def module: ModuleDef = ElasticSearchPluginModule

  override val info: PluginDescription = PluginDescription(Name.unsafe("elasticsearch"), BuildInfo.version)

  override val remoteContextResolution: RemoteContextResolution =
    RemoteContextResolution.fixedIOResource(
      contexts.elasticsearch         -> ioJsonContentOf("contexts/elasticsearch.json").memoizeOnSuccess,
      contexts.elasticsearchIndexing -> ioJsonContentOf("contexts/elasticsearch-indexing.json").memoizeOnSuccess
    )

  override def initialize(locator: Locator): Task[Plugin] = Task.delay(locator.get[ElasticSearchPlugin])

}
