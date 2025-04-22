package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.instances.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.{RealmProvisioning, Realms, RealmsConfig, RealmsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import io.circe.Json
import izumi.distage.model.definition.{Id, ModuleDef}
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[RealmsConfig].from { (cfg: AppConfig) => cfg.realms }

  make[Realms].from {
    (
        cfg: RealmsConfig,
        clock: Clock[IO],
        client: Client[IO] @Id("realm"),
        xas: Transactors
    ) =>
      val wellKnownResolver = realms.WellKnownResolver((uri: Uri) => client.expect[Json](uri)) _
      RealmsImpl(cfg, wellKnownResolver, xas, clock)
  }

  make[RealmProvisioning].from { (realms: Realms, cfg: RealmsConfig, serviceAccount: ServiceAccount) =>
    new RealmProvisioning(realms, cfg.provisioning, serviceAccount)
  }

  make[RealmsRoutes].from {
    (
        identities: Identities,
        realms: Realms,
        cfg: RealmsConfig,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new RealmsRoutes(identities, realms, aclCheck)(baseUri, cfg.pagination, cr, ordering)
  }

  make[Client[IO]].named("realm").fromResource(EmberClientBuilder.default[IO].build)

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/realms-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      realmCtx      <- ContextValue.fromFile("contexts/realms.json")
      realmsMetaCtx <- ContextValue.fromFile("contexts/realms-metadata.json")
    } yield RemoteContextResolution.fixed(contexts.realms -> realmCtx, contexts.realmsMetadata -> realmsMetaCtx)
  )

  many[PriorityRoute].add { (route: RealmsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 4, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
