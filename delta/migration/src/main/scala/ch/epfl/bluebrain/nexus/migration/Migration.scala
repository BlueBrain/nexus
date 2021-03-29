package ch.epfl.bluebrain.nexus.migration

import akka.actor.typed.ActorSystem
import akka.pattern.AskTimeoutException
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.ImportRealm
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{CassandraConfig, SaveProgressConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.EvaluationTimeout
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, RunResult}
import ch.epfl.bluebrain.nexus.migration.Migration._
import ch.epfl.bluebrain.nexus.migration.instances._
import ch.epfl.bluebrain.nexus.migration.replay.{ReplayMessageEvents, ReplaySettings}
import ch.epfl.bluebrain.nexus.migration.v1_4.SourceSanitizer
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent.EmptyEvent
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.{OrganizationEvent, ProjectEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.PermissionsEvent.{PermissionsAppended, PermissionsDeleted, PermissionsReplaced, PermissionsSubtracted}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.{AclEvent, PermissionsEvent, RealmEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.Event
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.Event._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.{EventDeserializationFailed, ToMigrateEvent}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.optics.JsonPath.root
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import java.util.UUID
import scala.concurrent.duration._
import scala.util.Try

/**
  * Migration module from v1.4 to v1.5
  */
final class Migration(
    replayMessageEvents: ReplayMessageEvents,
    projection: Projection[ToMigrateEvent],
    persistProgressConfig: SaveProgressConfig,
    clock: MutableClock,
    uuidF: MutableUUIDF,
    permissions: Permissions,
    acls: Acls,
    realms: Realms,
    projects: Projects,
    organizations: Organizations,
    resources: Resources,
    schemas: Schemas,
    resolvers: Resolvers,
    storageMigration: StoragesMigration,
    fileMigration: FilesMigration,
    elasticSearchViewsMigration: ElasticSearchViewsMigration,
    blazegraphViewsMigration: BlazegraphViewsMigration
)(implicit scheduler: Scheduler) {

  implicit val projectionId: ViewProjectionId = ViewProjectionId("migration-v1.5")

  // Project cache to avoid to call the project cache each time
  private val cache = collection.mutable.Map[UUID, ProjectRef]()

  /**
    * Start the migration from the stored offset
    */
  def start: Stream[Task, Unit] =
    Stream.eval(projection.progress(projectionId)).flatMap { progress =>
      logger.info(s"Starting migration at offset ${progress.offset}")
      replayMessageEvents
        .run(progress.offset)
        .runAsync(process)
        .persistProgress(
          progress,
          projection,
          persistProgressConfig
        )
        .void
    }

  private def process(event: ToMigrateEvent): Task[RunResult] = {
    event match {
      case p: PermissionsEvent           => processPermission(p)
      case a: AclEvent                   => processAcl(a)
      case r: RealmEvent                 => processRealm(r)
      case o: OrganizationEvent          => processOrganization(o)
      case p: ProjectEvent               => processProject(p)
      case e: Event                      => processResource(e)
      case e: EventDeserializationFailed => Task.raiseError(MigrationRejection(e))
    }
  }

  private def processPermission(permissionEvent: PermissionsEvent): Task[RunResult] = {
    clock.setInstant(permissionEvent.instant)
    val cRev                = permissionEvent.rev - 1
    implicit val s: Subject = permissionEvent.subject
    permissionEvent match {
      case PermissionsAppended(_, permissionsSet, _, _)  =>
        permissions.append(permissionsSet, cRev)
      case _: PermissionsDeleted                         =>
        permissions.delete(cRev)
      case PermissionsReplaced(_, permissionsSet, _, _)  =>
        permissions.replace(permissionsSet, cRev)
      case PermissionsSubtracted(_, permissionSet, _, _) =>
        permissions.subtract(permissionSet, cRev)
    }
  }.as(RunResult.Success).toTaskWith {
    case PermissionsRejection.PermissionsEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case PermissionsRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
  }

  private def processAcl(aclEvent: AclEvent): Task[RunResult] = {
    clock.setInstant(aclEvent.instant)
    implicit val s: Subject = aclEvent.subject
    val cRev                = aclEvent.rev - 1
    aclEvent match {
      case AclAppended(path, acl, _, _, _)   =>
        acls.append(Acl(path, acl.value), cRev)
      case AclDeleted(path, _, _, _)         =>
        acls.delete(path, cRev)
      case AclReplaced(path, acl, _, _, _)   =>
        acls.replace(Acl(path, acl.value), cRev)
      case AclSubtracted(path, acl, _, _, _) =>
        acls.subtract(Acl(path, acl.value), cRev)
    }
  }.as(RunResult.Success).toTaskWith {
    case AclRejection.AclEvaluationError(err @ EvaluationTimeout(_, _))          =>
      Task.raiseError(MigrationEvaluationTimeout(err))
    case AclRejection.IncorrectRev(_, provided, expected) if provided < expected => Task.pure(RunResult.Success)
  }

  private def processRealm(realmEvent: RealmEvent): Task[RunResult] = {
    clock.setInstant(realmEvent.instant)
    implicit val s: Subject = realmEvent.subject
    realmEvent match {
      case RealmCreated(
            id,
            rev,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          ) =>
        realms.importRealm(
          ImportRealm(
            id,
            rev - 1,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          )
        )
      case RealmUpdated(
            id,
            rev,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          ) =>
        realms.importRealm(
          ImportRealm(
            id,
            rev - 1,
            name,
            openIdConfig,
            issuer,
            keys,
            grantTypes,
            logo,
            authorizationEndpoint,
            tokenEndpoint,
            userInfoEndpoint,
            revocationEndpoint,
            endSessionEndpoint,
            instant,
            subject
          )
        )
      case RealmDeprecated(id, rev, _, _) =>
        realms.deprecate(id, rev - 1)
    }
  }.as(RunResult.Success).toTaskWith {
    case RealmRejection.RealmEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case _: RealmRejection.RealmAlreadyExists                                   => Task.pure(RunResult.Success)
    case _: RealmRejection.RealmAlreadyDeprecated                               => Task.pure(RunResult.Success)
    case RealmRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
  }

  private def fetchOrganizationLabel(orgUuid: UUID): IO[OrganizationRejection.OrganizationNotFound, Label] =
    organizations.fetch(orgUuid).map(_.value.label)

  private def fetchProjectRef(projectUuid: UUID, restartOnError: Boolean = true): IO[ProjectNotFound, ProjectRef] =
    IO.fromOption(cache.get(projectUuid), ProjectNotFound(projectUuid))
      .onErrorFallbackTo(
        projects
          .fetch(projectUuid)
          .map(_.value.ref)
          .tapEval(p => UIO.delay(cache.put(projectUuid, p)))
          // We retry as projects cache may not be ready after a restart and all projects must be migrated
          .tapError(e =>
            UIO.when(restartOnError)(
              UIO.delay(logger.error(s"Project $projectUuid can't be found, we will backoff and retry", e)) >>
                UIO.sleep(5.seconds)
            )
          )
          .onErrorRestartIf(_ => restartOnError)
      )

  private[migration] def processOrganization(organizationEvent: OrganizationEvent): Task[RunResult] = {
    clock.setInstant(organizationEvent.instant)
    implicit val s: Subject = organizationEvent.subject
    val cRev                = organizationEvent.rev - 1
    organizationEvent match {
      case OrganizationCreated(id, label, description, _, _)   =>
        uuidF.setUUID(id)
        organizations.create(label, description)
      case OrganizationUpdated(_, _, label, description, _, _) =>
        organizations.update(label, description, cRev)
      case OrganizationDeprecated(id, _, _, _)                 =>
        fetchOrganizationLabel(id).flatMap(organizations.deprecate(_, cRev))
    }
  }.as(RunResult.Success).toTaskWith {
    case OrganizationRejection.OrganizationEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case _: OrganizationRejection.OrganizationAlreadyExists                            => Task.pure(RunResult.Success)
    case _: OrganizationRejection.OrganizationIsDeprecated                             => Task.pure(RunResult.Success)
    case OrganizationRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
  }

  private[migration] def processProject(projectEvent: ProjectEvent): Task[RunResult] = {
    clock.setInstant(projectEvent.instant)
    implicit val s: Subject = projectEvent.subject
    val cRev                = projectEvent.rev - 1
    projectEvent match {
      case ProjectCreated(id, label, _, organizationLabel, description, apiMappings, base, vocab, _, _) =>
        uuidF.setUUID(id)
        val projectFields = ProjectFields(
          description,
          ApiMappings(apiMappings),
          Some(base),
          Some(vocab)
        )
        val projectRef    = ProjectRef(organizationLabel, label)
        projects.create(ProjectRef(organizationLabel, label), projectFields) <* UIO.delay(cache.put(id, projectRef))
      case ProjectUpdated(id, _, description, apiMappings, base, vocab, _, _, _)                        =>
        val projectFields = ProjectFields(
          description,
          ApiMappings(apiMappings),
          Some(base),
          Some(vocab)
        )
        fetchProjectRef(id).flatMap(projects.update(_, cRev, projectFields))
      case ProjectDeprecated(id, _, _, _)                                                               =>
        fetchProjectRef(id).flatMap(projects.deprecate(_, cRev))
    }
  }.as(RunResult.Success).toTaskWith {
    case ProjectRejection.ProjectEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case _: ProjectRejection.ProjectAlreadyExists                                 => Task.pure(RunResult.Success)
    case _: ProjectRejection.ProjectIsDeprecated                                  => Task.pure(RunResult.Success)
    case ProjectRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
  }

  // Replace id by the expanded one previous computed
  private def fixId(source: Json, id: Iri): Json =
    root.`@id`.string.modify(_ => id.toString)(source)

  private def fixSource(source: Json): Json = SourceSanitizer.sanitize(source)

  // Replace project uuids in cross-project resolvers by project refs, projects can have been deleted so we skip them
  private val replaceResolversProjectUuids: Json => Task[Json] = root.projects.arr.modifyF { uuids =>
    uuids.traverseFilter { json =>
      json.asString match {
        case Some(str) =>
          IO.fromTry(Try(UUID.fromString(str)))
            .flatMap { uuid =>
              fetchProjectRef(uuid)
                .map(r => Some(r.asJson))
                .onErrorFallbackTo(UIO.delay(logger.warn(s"Project $uuid not found, we filter it")) >> UIO.pure(None))
            }
            .onErrorFallbackTo(UIO.pure(Some(json)))
        case None      => IO.raiseError(ProjectNotFound(json)).leftWiden[ProjectRejection].toTaskWith(projectTimeoutRecover)
      }
    }
  }

  // Replace project uuids in aggregate views, some projects seem to have been deleted so we skip them
  private val replaceViewsProjectUuids: Json => Task[Json] = root.views.arr.modifyF { views =>
    views.traverseFilter { view =>
      view.asObject match {
        case Some(v) =>
          val projectUuid = for {
            project <- v("project")
            p       <- project.asString
            uuid    <- Try(UUID.fromString(p)).toOption
          } yield uuid
          val viewId      = v("viewId").flatMap {
            _.asString match {
              case Some(x) =>
                if (x.startsWith("nxv:")) Some(x.replaceFirst("nxv:", Vocabulary.nxv.base.toString).asJson)
                else Some(x.asJson)
              case None    => None
            }
          }
          projectUuid match {
            case Some(u) =>
              fetchProjectRef(u, restartOnError = false)
                .map { p =>
                  viewId.map { x => v.add("project", p.asJson).add("viewId", x).asJson }
                } // we override with ref
                .onErrorFallbackTo(UIO.delay(logger.warn(s"Project $u not found, we filter it")) >> IO.pure(None))
            case None    => IO.pure(Some(view)) // project ref here, we change nothing
          }
        case None    => IO.raiseError(MigrationRejection(view)) // should not happen
      }
    }
  }

  private def getIdentities(source: Json): Set[Identity] =
    root.identities.arr.getOption(source).fold(Set.empty[Identity])(_.flatMap(_.as[Identity].toOption).toSet)

  private def fixResolverSource(source: Json): Task[Json] = {
    // Resolver id can't be expanded anymore so we give the one already computed in previous version
    val s =
      root.`@id`.string.modify(idPayload =>
        if (idPayload.startsWith("nxv:")) idPayload.replaceFirst("nxv:", Vocabulary.nxv.base.toString) else idPayload
      )(source)
    replaceResolversProjectUuids(SourceSanitizer.sanitize(s))
  }

  private def fixIdsAndSource(source: Json): Json = {
    // Default ids can't be expanded anymore so we give the one already computed in previous version
    val s =
      root.`@id`.string.modify(idPayload =>
        if (idPayload.startsWith("nxv:")) idPayload.replaceFirst("nxv:", Vocabulary.nxv.base.toString) else idPayload
      )(
        source
      )
    SourceSanitizer.sanitize(s)
  }

  private def extractViewUuid(source: Json) = {
    val uuid = root._uuid.string.getOption(source).flatMap { value =>
      Try(UUID.fromString(value)).toOption
    }
    IO.fromOption(uuid, MigrationRejection("UUID was not present in source or is invalid"))
  }

  // Functions to recover and ignore errors resulting from restarts where events get replayed
  private def schemaErrorRecover: PartialFunction[SchemaRejection, Task[RunResult]] = {
    case SchemaRejection.SchemaEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case _: SchemaRejection.SchemaAlreadyExists                                  => Task.pure(RunResult.Success)
    case SchemaRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
    case _: SchemaRejection.SchemaIsDeprecated                                   => Task.pure(RunResult.Success)
  }

  private def projectTimeoutRecover[A]: PartialFunction[ProjectRejection, Task[A]] = {
    case ProjectRejection.ProjectEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

  }

  private def resolverErrorRecover: PartialFunction[ResolverRejection, Task[RunResult]] = {
    case ResolverRejection.ResolverEvaluationError(err @ EvaluationTimeout(_, _)) =>
      Task.raiseError(MigrationEvaluationTimeout(err))

    case _: ResolverRejection.ResolverAlreadyExists                                => Task.pure(RunResult.Success)
    case ResolverRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
    case _: ResolverRejection.ResolverIsDeprecated                                 => Task.pure(RunResult.Success)
  }

  private def resourceErrorRecover: PartialFunction[ResourceRejection, Task[RunResult]] = {
    case ResourceRejection.ResourceEvaluationError(err @ EvaluationTimeout(_, _))  =>
      Task.raiseError(MigrationEvaluationTimeout(err))
    case _: ResourceRejection.ResourceAlreadyExists                                => Task.pure(RunResult.Success)
    case ResourceRejection.IncorrectRev(provided, expected) if provided < expected => Task.pure(RunResult.Success)
    case _: ResourceRejection.ResourceIsDeprecated                                 => Task.pure(RunResult.Success)
  }

  private def exists(typesAsString: Set[String], iri: Iri): Boolean =
    typesAsString.exists { Iri(_).toOption.contains(iri) }

  private def exists(typesAsString: Set[String], f: Iri => Boolean): Boolean =
    typesAsString.exists { Iri(_).toOption.exists(f) }

  private def processResource(event: Event): Task[RunResult] = {
    clock.setInstant(event.instant)
    implicit val caller: Caller = Caller(event.subject, Set.empty)
    val cRev                    = event.rev - 1

    fetchProjectRef(event.project)
      .leftWiden[ProjectRejection]
      .toTaskWith(projectTimeoutRecover)
      .flatMap { projectRef =>
        {
          event match {
            // Schemas
            case Created(id, _, _, _, types, source, _, _) if exists(types, nxv.Schema)                     =>
              val fixedSource           = fixSource(source)
              def createSchema(s: Json) = schemas.create(id, projectRef, s)

              UIO.delay(logger.info(s"Create schema $id in project $projectRef")) >>
                createSchema(fixedSource)
                  .as(RunResult.Success)
                  .onErrorRecoverWith {
                    case SchemaRejection.UnexpectedSchemaId(_, idPayload)      =>
                      logger.warn(s"Fixing id when creating schema $id in $projectRef")
                      createSchema(fixId(fixedSource, id)).as(
                        Warnings.unexpectedId("schema", id, projectRef, idPayload)
                      )
                    case SchemaRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                      logger.warn(s"Fixing id when creating schema $id in $projectRef")
                      createSchema(fixId(fixedSource, id)).as(
                        Warnings.invalidId("schema", id, projectRef, i.getMessage)
                      )
                  }
                  .toTaskWith(schemaErrorRecover)
            case Updated(id, _, _, _, types, source, _, _) if exists(types, nxv.Schema)                     =>
              val fixedSource           = fixSource(source)
              def updateSchema(s: Json) = schemas.update(id, projectRef, cRev, s)
              updateSchema(fixedSource)
                .as(RunResult.Success)
                .onErrorRecoverWith {
                  case SchemaRejection.UnexpectedSchemaId(_, idPayload)      =>
                    logger.warn(s"Fixing id when updating schema $id in $projectRef")
                    updateSchema(fixId(fixedSource, id)).as(
                      Warnings.unexpectedId("schema", id, projectRef, idPayload)
                    )
                  case SchemaRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                    logger.warn(s"Fixing id when updating schema $id in $projectRef")
                    updateSchema(fixId(fixedSource, id)).as(
                      Warnings.invalidId("schema", id, projectRef, i.getMessage)
                    )
                }
                .toTaskWith(schemaErrorRecover)
            case Deprecated(id, _, _, _, types, _, _) if exists(types, nxv.Schema)                          =>
              UIO.delay(logger.info(s"Deprecate schema $id in project $projectRef")) >>
                schemas.deprecate(id, projectRef, cRev).toTaskWith(_ => RunResult.Success, schemaErrorRecover)
            // Resolvers
            case Created(id, _, _, _, types, source, _, _) if exists(types, nxv.Resolver)                   =>
              val resolverCaller               = caller.copy(identities = getIdentities(source))
              def createResolver(source: Json) = resolvers.create(id, projectRef, source)(resolverCaller)

              UIO.delay(logger.info(s"Create resolver $id in project $projectRef")) >>
                fixResolverSource(source).flatMap { s =>
                  createResolver(s).as(RunResult.Success)
                    .onErrorRecoverWith {
                      case ResolverRejection.UnexpectedResolverId(_, payloadId)    =>
                        logger.warn(s"Fixing id when creating resolver $id in $projectRef")
                        createResolver(fixId(s, id))
                          .as(Warnings.unexpectedId("resolver", id, projectRef, payloadId))
                      case ResolverRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                        logger.warn(s"Fixing id when creating resolver $id in $projectRef")
                        createResolver(fixId(s, id)).as(
                          Warnings.invalidId("schema", id, projectRef, i.getMessage)
                        )
                    }
                    .toTaskWith(resolverErrorRecover)
                }
            case Updated(id, _, _, _, types, source, _, _) if exists(types, nxv.Resolver)                   =>
              val resolverCaller               = caller.copy(identities = getIdentities(source))
              def updateResolver(source: Json) = resolvers.update(id, projectRef, cRev, source)(resolverCaller)

              UIO.delay(logger.info(s"Update resolver $id in project $projectRef")) >>
                fixResolverSource(source).flatMap { s =>
                  updateResolver(s)
                    .as(RunResult.Success)
                    .onErrorRecoverWith {
                      case ResolverRejection.UnexpectedResolverId(_, payloadId)    =>
                        logger.warn(s"Fixing id when updating resolver $id in $projectRef")
                        updateResolver(fixId(s, id)).as(Warnings.unexpectedId("resolver", id, projectRef, payloadId))
                      case ResolverRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                        logger.warn(s"Fixing id when updating resolver $id in $projectRef")
                        updateResolver(fixId(s, id)).as(
                          Warnings.invalidId("schema", id, projectRef, i.getMessage)
                        )
                    }
                    .toTaskWith(resolverErrorRecover)
                }
            case Deprecated(id, _, _, rev, types, _, _) if exists(types, nxv.Resolver)                      =>
              UIO.delay(logger.info(s"Deprecate resolver $id in project $projectRef")) >>
                resolvers
                  .deprecate(id, projectRef, rev - 1)
                  .toTaskWith(_ => RunResult.Success, resolverErrorRecover)
            // ElasticSearch views
            case Created(id, _, _, _, types, source, _, _) if exists(types, elasticsearchViews.contains(_)) =>
              for {
                _           <- UIO.delay(logger.info(s"Create elasticsearch view $id in project $projectRef"))
                fixedSource <- replaceViewsProjectUuids(
                                 SourceSanitizer.replaceContext(
                                   iri"https://bluebrain.github.io/nexus/contexts/view.json",
                                   iri"https://bluebrain.github.io/nexus/contexts/elasticsearch.json"
                                 )(fixIdsAndSource(source))
                               )
                uuid        <- extractViewUuid(source)
                _           <- UIO.delay(uuidF.setUUID(uuid))
                r           <- elasticSearchViewsMigration.create(id, projectRef, fixedSource)
              } yield r
            case Updated(id, _, _, _, types, source, _, _) if exists(types, elasticsearchViews.contains(_)) =>
              for {
                _           <- UIO.delay(logger.info(s"Update elasticsearch view $id in project $projectRef"))
                fixedSource <- replaceViewsProjectUuids(
                                 SourceSanitizer.replaceContext(
                                   iri"https://bluebrain.github.io/nexus/contexts/view.json",
                                   iri"https://bluebrain.github.io/nexus/contexts/elasticsearch.json"
                                 )(fixIdsAndSource(source))
                               )
                r           <- elasticSearchViewsMigration.update(id, projectRef, cRev, fixedSource)
              } yield r
            case Deprecated(id, _, _, _, types, _, _) if exists(types, elasticsearchViews.contains(_))      =>
              UIO.delay(logger.info(s"Deprecate elasticsearch view $id in project $projectRef")) >>
                elasticSearchViewsMigration.deprecate(id, projectRef, cRev)
            // Blazegraph views
            case Created(id, _, _, _, types, source, _, _) if exists(types, blazegraphViews.contains(_))    =>
              for {
                _           <- UIO.delay(logger.info(s"Create blazegraph view $id in project $projectRef"))
                fixedSource <- replaceViewsProjectUuids(
                                 SourceSanitizer.replaceContext(
                                   iri"https://bluebrain.github.io/nexus/contexts/view.json",
                                   iri"https://bluebrain.github.io/nexus/contexts/blazegraph.json"
                                 )(fixIdsAndSource(source))
                               )
                uuid        <- extractViewUuid(source)
                _           <- UIO.delay(uuidF.setUUID(uuid))
                r           <- blazegraphViewsMigration.create(id, projectRef, fixedSource)
              } yield r
            case Updated(id, _, _, _, types, source, _, _) if exists(types, blazegraphViews.contains(_))    =>
              for {
                _           <- UIO.delay(logger.info(s"Update blazegraph view $id in project $projectRef"))
                fixedSource <- replaceViewsProjectUuids(
                                 SourceSanitizer.replaceContext(
                                   iri"https://bluebrain.github.io/nexus/contexts/view.json",
                                   iri"https://bluebrain.github.io/nexus/contexts/blazegraph.json"
                                 )(fixIdsAndSource(source))
                               )
                r           <- blazegraphViewsMigration.update(id, projectRef, cRev, fixedSource)
              } yield r
            case Deprecated(id, _, _, _, types, _, _) if exists(types, blazegraphViews.contains(_))         =>
              UIO.delay(logger.info(s"Deprecate blazegraph view $id in project $projectRef")) >>
                blazegraphViewsMigration.deprecate(id, projectRef, cRev)
            // Composite views
            case Created(_, _, _, _, types, _, _, _) if exists(types, compositeViews)                       =>
              IO.pure(RunResult.Success)
            case Updated(_, _, _, _, types, _, _, _) if exists(types, compositeViews)                       =>
              IO.pure(RunResult.Success)
            case Deprecated(_, _, _, _, types, _, _) if exists(types, compositeViews)                       =>
              IO.pure(RunResult.Success)
            // Storages
            case Created(id, _, _, _, types, source, _, _) if exists(types, storageTypes.contains(_))       =>
              val fixedSource = fixIdsAndSource(source)
              UIO.delay(logger.info(s"Create storage $id in project $projectRef")) >>
                storageMigration.migrate(id, projectRef, None, fixedSource)
            case Updated(id, _, _, _, types, source, _, _) if exists(types, storageTypes.contains(_))       =>
              val fixedSource = fixIdsAndSource(source)
              UIO.delay(logger.info(s"Update storage $id in project $projectRef")) >>
                storageMigration.migrate(id, projectRef, Some(cRev), fixedSource)
            case Deprecated(id, _, _, _, types, _, _) if exists(types, storageTypes.contains(_))            =>
              UIO.delay(logger.info(s"Deprecate storage $id in project $projectRef")) >>
                storageMigration.migrateDeprecate(id, projectRef, cRev)
            // Data resources
            case Created(id, _, _, schema, _, source, _, _)                                                 =>
              val schemaSegment                                                =
                if (schema.original == unsconstrained) Vocabulary.schemas.resources
                else schema.original
              val fixedSource                                                  = fixSource(source)
              def createResource(s: Json): IO[ResourceRejection, DataResource] =
                resources.create(id, projectRef, schemaSegment, s)

              UIO.delay(logger.info(s"Create resource $id in project $projectRef")) >>
                createResource(fixedSource)
                  .as(RunResult.Success)
                  .onErrorRecoverWith {
                    case ResourceRejection.UnexpectedResourceId(_, payloadId)    =>
                      logger.warn(s"Fixing id when creating resource $id in $projectRef")
                      createResource(fixId(fixedSource, id)).as(
                        Warnings.unexpectedId("resource", id, projectRef, payloadId)
                      )
                    case ResourceRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                      logger.warn(s"Fixing id when creating resource $id in $projectRef")
                      createResource(fixId(fixedSource, id)).as(
                        Warnings.invalidId("schema", id, projectRef, i.getMessage)
                      )

                  }
                  .toTaskWith(resourceErrorRecover)
            case Updated(id, _, _, _, _, source, _, _)                                                      =>
              val fixedSource             = fixSource(source)
              def updateResource(s: Json) = resources.update(id, projectRef, None, cRev, s)
              UIO.delay(logger.info(s"Update resource $id in project $projectRef")) >>
                updateResource(fixedSource)
                  .as(RunResult.Success)
                  .onErrorRecoverWith {
                    case ResourceRejection.UnexpectedResourceId(_, payloadId) =>
                      logger.warn(s"Fixing id when updating resource $id in $projectRef")
                      updateResource(fixId(fixedSource, id)).as(
                        Warnings.unexpectedId("resource", id, projectRef, payloadId)
                      )

                    case ResourceRejection.InvalidJsonLdFormat(_, i: InvalidIri) =>
                      logger.warn(s"Fixing id when updating resource $id in $projectRef")
                      updateResource(fixId(fixedSource, id)).as(
                        Warnings.invalidId("schema", id, projectRef, i.getMessage)
                      )
                  }
                  .toTaskWith(resourceErrorRecover)
            case Deprecated(id, _, _, _, _, _, _)                                                           =>
              UIO.delay(logger.info(s"Deprecate resource $id in project $projectRef")) >>
                resources.deprecate(id, projectRef, None, cRev).as(successResult).toTaskWith(resourceErrorRecover)
            // Tagging
            case TagAdded(id, _, _, _, targetRev, tag, _, _)                                                =>
              // No information on resource type in tag event, so we try for the different types :'(
              val operations = List(
                resources
                  .tag(id, projectRef, None, tag, targetRev, cRev)
                  .as(successResult)
                  .toTaskWith(resourceErrorRecover),
                schemas.tag(id, projectRef, tag, targetRev, cRev).as(successResult).toTaskWith(schemaErrorRecover),
                fileMigration.migrateTag(id, projectRef, tag, targetRev, cRev),
                resolvers.tag(id, projectRef, tag, targetRev, cRev).as(successResult).toTaskWith(resolverErrorRecover),
                storageMigration.migrateTag(id, projectRef, tag, targetRev, cRev),
                elasticSearchViewsMigration.tag(id, projectRef, tag, targetRev, cRev),
                blazegraphViewsMigration.tag(id, projectRef, tag, targetRev, cRev)
              )

              val tagRejection = MigrationRejection(
                Json
                  .obj("reason" -> Json.fromString(s"Resource/Schema/Resolvers/Storage/File $id could not be tagged."))
              )

              UIO.delay(logger.info(s"Tag $id in project $projectRef")) >>
                Task.tailRecM(operations) {
                  case Nil          => Task.raiseError(tagRejection)
                  case head :: Nil  => head.map(_ => Right(successResult))
                  case head :: tail => head.map(_ => Right(successResult)).onErrorFallbackTo(IO.pure(Left(tail)))
                }
            case FileCreated(id, _, _, storage, attributes, _, _)                                           =>
              UIO.delay(logger.info(s"Create file $id in project $projectRef")) >>
                fileMigration.migrate(id, projectRef, None, storage, attributes).as(RunResult.Success)
            case FileUpdated(id, _, _, storage, _, attributes, _, _)                                        =>
              UIO.delay(logger.info(s"Update file $id in project $projectRef")) >>
                fileMigration.migrate(id, projectRef, Some(cRev), storage, attributes).as(RunResult.Success)
            case FileDigestUpdated(id, _, _, _, _, digest, _, _)                                            =>
              UIO.delay(logger.info(s"Update file digest for $id in project $projectRef")) >>
                fileMigration.fileDigestUpdated(id, projectRef, cRev, digest).as(RunResult.Success)
            case FileAttributesUpdated(id, _, _, _, _, attributes, _, _)                                    =>
              UIO.delay(logger.info(s"Update file attributes for $id in project $projectRef")) >>
                fileMigration.fileAttributesUpdated(id, projectRef, cRev, attributes).as(RunResult.Success)
          }
        }
      }
  }

}

object Migration {

  private val logger: Logger = Logger[Migration]

  private val unsconstrained = schemas + "unconstrained.json"

  val elasticsearchViews = Set(
    nxv + "AggregateElasticSearchView",
    nxv + "ElasticSearchView"
  )

  val blazegraphViews = Set(
    nxv + "AggregateSparqlView",
    nxv + "SparqlView"
  )

  val compositeViews = nxv + "CompositeView"

  private val storageTypes = Set(nxv + "Storage", nxv + "RemoteDiskStorage", nxv + "DiskStorage", nxv + "S3Storage")

  private val successResult: RunResult = RunResult.Success

  implicit class MigrationIO[R: Encoder, A](io: IO[R, A]) {

    def toTaskWith(recover: PartialFunction[R, Task[A]]): Task[A] =
      toTaskWith(identity, recover)

    def toTaskWith[B](f: A => B, recover: PartialFunction[R, Task[B]]): Task[B] =
      io.redeemWith(
        c => recover.applyOrElse(c, (cc: R) => Task.raiseError(MigrationRejection.apply(cc))),
        a => IO.pure(f(a))
      ).tapError { e =>
        UIO.delay(logger.error(s"We got an error while evaluation, we will retry in case of a timeout", e))
      }.onErrorRestartIf {
        // We should try the event again if we get a timeout
        case _: AskTimeoutException        => true
        case _: MigrationEvaluationTimeout => true
        case _                             => false
  }

  }

  private def replayEvents(config: Config)(implicit as: ActorSystem[Nothing]): Task[ReplayMessageEvents] =
    ReplayMessageEvents(ReplaySettings.from(config))(as, Clock[UIO])

  private def startMigration(migration: Migration, config: Config)(implicit
      as: ActorSystem[Nothing],
      sc: Scheduler
  ) = {
    implicit val uuidF: UUIDF = UUIDF.random
    val retryStrategyConfig   =
      ConfigSource.fromConfig(config).at("migration.retry-strategy").loadOrThrow[RetryStrategyConfig]
    DaemonStreamCoordinator.run(
      "MigrationStream",
      stream = migration.start,
      retryStrategy = RetryStrategy.retryOnNonFatal(retryStrategyConfig, logger, "data migrating")
    )
  }

  def apply(
      clock: MutableClock,
      uuidF: MutableUUIDF,
      permissions: Permissions,
      acls: Acls,
      realms: Realms,
      projects: Projects,
      organizations: Organizations,
      resources: Resources,
      schemas: Schemas,
      resolvers: Resolvers,
      storageMigration: StoragesMigration,
      fileMigration: FilesMigration,
      elasticSearchViewsMigration: ElasticSearchViewsMigration,
      blazegraphViewsMigration: BlazegraphViewsMigration,
      cassandraConfig: CassandraConfig
  )(implicit as: ActorSystem[Nothing], s: Scheduler): Task[Migration] = {

    implicit val toMigrateEventDecoder: Decoder[ToMigrateEvent] = Decoder.instance { _ =>
      // We don't care about this value, we just want to be able to restart after a crash
      Right(EmptyEvent)
    }

    val config                    = ConfigFactory.load("migration.conf")
    val persistenceProgressConfig =
      ConfigSource.fromConfig(config).at("migration.projection").loadOrThrow[SaveProgressConfig]

    def throwableToString(t: Throwable): String = t match {
      case MigrationRejection(json) => json.noSpaces                    // Module rejections
      case _                        => Projection.stackTraceAsString(t) // Other errors where the stacktrace may be useful
    }
    for {
      replay     <- replayEvents(config)
      projection <- Projection.cassandra(cassandraConfig, ToMigrateEvent.empty, throwableToString)
      migration   = new Migration(
                      replay,
                      projection,
                      persistenceProgressConfig,
                      clock,
                      uuidF,
                      permissions,
                      acls,
                      realms,
                      projects,
                      organizations,
                      resources,
                      schemas,
                      resolvers,
                      storageMigration,
                      fileMigration,
                      elasticSearchViewsMigration,
                      blazegraphViewsMigration
                    )
      _          <- startMigration(migration, config)(as, s)
    } yield migration
  }

}
