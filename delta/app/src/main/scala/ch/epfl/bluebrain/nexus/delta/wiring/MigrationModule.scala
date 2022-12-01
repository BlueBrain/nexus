package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewCommand, BlazegraphViewEvent, BlazegraphViewRejection, BlazegraphViewState}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewCommand, CompositeViewEvent, CompositeViewRejection, CompositeViewState}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewCommand, ElasticSearchViewEvent, ElasticSearchViewRejection, ElasticSearchViewState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.StoragePluginConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileCommand, FileEvent, FileRejection, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageCommand, StorageEvent, StorageRejection, StorageState}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.migration.MigrationLog
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{OrganizationCommand, OrganizationEvent, OrganizationRejection, OrganizationState}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.labelId
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.{PermissionsCommand, PermissionsEvent, PermissionsRejection, PermissionsState}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ProjectCommand, ProjectEvent, ProjectRejection, ProjectState}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{RealmCommand, RealmEvent, RealmRejection, RealmState}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverCommand, ResolverEvent, ResolverRejection, ResolverState}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import izumi.distage.model.definition.ModuleDef
import monix.bio.{IO, UIO}

/**
  * Migration wiring
  */
// $COVERAGE-OFF$
object MigrationModule extends ModuleDef {

  // Permissions
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    MigrationLog.global[Label, PermissionsState, PermissionsCommand, PermissionsEvent, PermissionsRejection](
      Permissions.definition(cfg.permissions.minimum)(clock),
      _ => labelId,
      identity,
      (e, _) => e,
      cfg.permissions.eventLog,
      xas
    )
  }

  // Realms
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    MigrationLog.global[Label, RealmState, RealmCommand, RealmEvent, RealmRejection](
      Realms.definition(
        _ => IO.terminate(new IllegalStateException("Realm command evaluation should not happen")),
        (_, _) => IO.terminate(new IllegalStateException("Realm command evaluation should not happen"))
      )(clock),
      e => e.label,
      identity,
      (e, _) => e,
      cfg.realms.eventLog,
      xas
    )
  }

  // ACLs
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    MigrationLog.global[AclAddress, AclState, AclCommand, AclEvent, AclRejection](
      Acls.definition(
        UIO.terminate(new IllegalStateException("ACL command evaluation should not happen")),
        _ => IO.terminate(new IllegalStateException("ACL command evaluation should not happen"))
      )(clock),
      e => e.address,
      identity,
      (e, _) => e,
      cfg.acls.eventLog,
      xas
    )
  }

  // Organizations
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO], uuidF: UUIDF) =>
    MigrationLog.global[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection](
      Organizations.definition(clock, uuidF),
      e => e.label,
      identity,
      (e, _) => e,
      cfg.organizations.eventLog,
      xas
    )
  }

  // Projects
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO], uuidF: UUIDF) =>
    MigrationLog.scoped[ProjectRef, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection](
      Projects.definition(_ => IO.terminate(new IllegalStateException("Project command evaluation should not happen")))(
        clock,
        uuidF
      ),
      e => e.project,
      identity,
      (e, _) => e,
      cfg.projects.eventLog,
      xas
    )
  }

  // Schemas
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    val jsonldApi: JsonLdApi = JsonLdJavaApi.lenient
    MigrationLog.scoped[Iri, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection](
      Schemas.definition(jsonldApi, clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.resources.eventLog,
      xas
    )
  }

  // Resolvers
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    MigrationLog.scoped[Iri, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection](
      Resolvers.definition((_, _, _) =>
        IO.terminate(new IllegalStateException("Resolver command evaluation should not happen"))
      )(clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.resolvers.eventLog,
      xas
    )
  }

  // Resources
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    // Should not be called as no evaluation will be performed
    val resourceResolution: ResourceResolution[Schema] = null
    val jsonldApi: JsonLdApi                           = JsonLdJavaApi.lenient
    MigrationLog.scoped[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection](
      Resources.definition(resourceResolution)(jsonldApi, clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.resources.eventLog,
      xas
    )
  }

  // Elasticsearch views
  many[MigrationLog].add { (cfg: ElasticSearchViewsConfig, xas: Transactors, clock: Clock[UIO], uuidF: UUIDF) =>
    MigrationLog.scoped[
      Iri,
      ElasticSearchViewState,
      ElasticSearchViewCommand,
      ElasticSearchViewEvent,
      ElasticSearchViewRejection
    ](
      ElasticSearchViews.definition((_, _, _) =>
        IO.terminate(new IllegalStateException("ElasticSearchView command evaluation should not happen"))
      )(clock, uuidF),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.eventLog,
      xas
    )
  }

  // Blazegraph views
  many[MigrationLog].add { (cfg: BlazegraphViewsConfig, xas: Transactors, clock: Clock[UIO], uuidF: UUIDF) =>
    MigrationLog.scoped[Iri, BlazegraphViewState, BlazegraphViewCommand, BlazegraphViewEvent, BlazegraphViewRejection](
      BlazegraphViews.definition(_ =>
        IO.terminate(new IllegalStateException("BlazegraphView command evaluation should not happen"))
      )(clock, uuidF),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.eventLog,
      xas
    )
  }

  // Composite views
  many[MigrationLog].add {
    (cfg: CompositeViewsConfig, xas: Transactors, crypto: Crypto, clock: Clock[UIO], uuidF: UUIDF) =>
      MigrationLog.scoped[Iri, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection](
        CompositeViews
          .definition(
            (_, _, _) => IO.terminate(new IllegalStateException("CompositeView command evaluation should not happen")),
            crypto
          )(clock, uuidF),
        e => e.id,
        identity,
        (e, _) => e,
        cfg.eventLog,
        xas
      )

  }

  // Storages
  many[MigrationLog].add { (cfg: StoragePluginConfig, xas: Transactors, clock: Clock[UIO], crypto: Crypto) =>
    MigrationLog.scoped[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection](
      Storages.definition(
        cfg.storages.storageTypeConfig,
        (_, _) => IO.terminate(new IllegalStateException("Storage command evaluation should not happen")),
        UIO.terminate(new IllegalStateException("Storage command evaluation should not happen")),
        crypto
      )(clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.storages.eventLog,
      xas
    )
  }

  // Files
  many[MigrationLog].add { (cfg: StoragePluginConfig, xas: Transactors, clock: Clock[UIO]) =>
    MigrationLog.scoped[Iri, FileState, FileCommand, FileEvent, FileRejection](
      Files.definition(clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.files.eventLog,
      xas
    )
  }

}
// $COVERAGE-ON$
