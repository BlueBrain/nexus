package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[Archives].fromEffect {
    (
        projects: Projects,
        cfg: ArchivePluginConfig,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        rcr: RemoteContextResolution @Id("aggregate"),
        clock: Clock[UIO]
    ) =>
      Archives(projects, cfg)(as, uuidF, rcr, clock)
  }

  many[RemoteContextResolution].addEffect(ioJsonContentOf("contexts/archives.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.archives -> ctx.topContextValueOrEmpty)
  })

  many[ApiMappings].add(Archives.mappings)

}
