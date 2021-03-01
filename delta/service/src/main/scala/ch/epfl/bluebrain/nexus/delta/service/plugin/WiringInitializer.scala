package ch.epfl.bluebrain.nexus.delta.service.plugin

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import izumi.distage.modules.DefaultModule
import monix.bio.{IO, Task}

object WiringInitializer {

  /**
    * Combines the [[ModuleDef]] of the passed ''serviceModule'' with the ones provided by the plugins.
    * Afterwards initializes the [[Plugin]]s and the [[Locator]].
    */
  def apply(
      serviceModule: ModuleDef,
      pluginsDef: List[PluginDef]
  )(implicit classLoader: ClassLoader): IO[PluginInitializationError, (List[Plugin], Locator)] = {
    val pluginsInfoModule = new ModuleDef {
      make[List[PluginDescription]].from(pluginsDef.map(_.info))
      make[RemoteContextResolution].from(
        RemoteContextResolution
          .fixedIOResource(
            contexts.acls          -> ioJsonContentOf("contexts/acls.json").memoizeOnSuccess,
            contexts.error         -> ioJsonContentOf("contexts/error.json").memoizeOnSuccess,
            contexts.identities    -> ioJsonContentOf("contexts/identities.json").memoizeOnSuccess,
            contexts.offset        -> ioJsonContentOf("contexts/offset.json").memoizeOnSuccess,
            contexts.organizations -> ioJsonContentOf("contexts/organizations.json").memoizeOnSuccess,
            contexts.permissions   -> ioJsonContentOf("contexts/permissions.json").memoizeOnSuccess,
            contexts.projects      -> ioJsonContentOf("contexts/projects.json").memoizeOnSuccess,
            contexts.realms        -> ioJsonContentOf("contexts/realms.json").memoizeOnSuccess,
            contexts.resolvers     -> ioJsonContentOf("contexts/resolvers.json").memoizeOnSuccess,
            contexts.metadata      -> ioJsonContentOf("contexts/metadata.json").memoizeOnSuccess,
            contexts.search        -> ioJsonContentOf("contexts/search.json").memoizeOnSuccess,
            contexts.shacl         -> ioJsonContentOf("contexts/shacl.json").memoizeOnSuccess,
            contexts.statistics    -> ioJsonContentOf("contexts/statistics.json").memoizeOnSuccess,
            contexts.tags          -> ioJsonContentOf("contexts/tags.json").memoizeOnSuccess,
            contexts.version       -> ioJsonContentOf("contexts/version.json").memoizeOnSuccess
          )
          .merge(pluginsDef.map(_.remoteContextResolution): _*)
      )
    }
    val appModules        = (serviceModule :: pluginsInfoModule :: pluginsDef.map(_.module)).merge

    // workaround for: java.lang.NoClassDefFoundError: zio/blocking/package$Blocking$Service
    implicit val defaultModule: DefaultModule[Task] = DefaultModule.empty
    Injector[Task]()
      .produce(appModules, Roots.Everything)
      .use(locator => IO.traverse(pluginsDef)(_.initialize(locator)).map(_ -> locator))
      .mapError(e => PluginInitializationError(e.getMessage))
  }
}
