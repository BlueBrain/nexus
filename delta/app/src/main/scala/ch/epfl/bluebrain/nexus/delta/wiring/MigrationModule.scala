package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
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
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverCommand, ResolverEvent, ResolverRejection, ResolverState}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{Resources, ValidateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.migration.MigrationLogHelpers
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
      (e, _) => MigrationLogHelpers.injectResolverDefaults(cfg.resolvers.defaults)(e),
      cfg.resolvers.eventLog,
      xas
    )
  }

  // Resources
  many[MigrationLog].add { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    // Should not be called as no evaluation will be performed
    val resourceValidator: ValidateResource = null
    MigrationLog.scoped[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection](
      Resources.definition(resourceValidator)(clock),
      e => e.id,
      identity,
      (e, _) => e,
      cfg.resources.eventLog,
      xas
    )
  }

}
// $COVERAGE-ON$
