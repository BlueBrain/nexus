package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts, CompositeViewEvent}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import distage.ModuleDef
import monix.bio.UIO

import scala.annotation.unused

@SuppressWarnings(Array("UnusedMethodParameter"))
class CompositeViewsPluginModule(@unused priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[EventLog[Envelope[CompositeViewEvent]]].fromEffect { databaseEventLog[CompositeViewEvent](_, _) }

  make[CompositeViews].fromEffect {
    (
        config: CompositeViewsConfig,
        eventLog: EventLog[Envelope[CompositeViewEvent]],
        permissions: Permissions,
        orgs: Organizations,
        projects: Projects,
        acls: Acls,
        contextResolution: ResolverContextResolution,
        uuidF: UUIDF,
        clock: Clock[UIO],
        as: ActorSystem[Nothing],
        baseUri: BaseUri,
        crypto: Crypto
    ) =>
      CompositeViews(config, eventLog, permissions, orgs, projects, acls, contextResolution, crypto)(
        uuidF,
        clock,
        as,
        baseUri
      )
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/composite-views-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      ctx     <- ContextValue.fromFile("contexts/composite-views.json")
      metaCtx <- ContextValue.fromFile("contexts/composite-views-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.compositeViews         -> ctx,
      contexts.compositeViewsMetadata -> metaCtx
    )
  )

}
