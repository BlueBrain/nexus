package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives.{expandIri, moduleType}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, Projects, ResourceIdCheck}
import ch.epfl.bluebrain.nexus.delta.sourcing.TransientEventDefinition
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import io.circe.Json
import monix.bio.{IO, UIO}

import scala.annotation.unused

/**
  * Archives module.
  *
  * @param aggregate       the underlying aggregate
  * @param projects        the projects module
  * @param archiveDownload the archive download logic
  * @param sourceDecoder   a source decoder for [[ArchiveValue]]
  * @param cfg             the archive plugin config
  * @param uuidF           the uuid generator
  * @param rcr             the archive remote context resolution
  */
class Archives(
    aggregate: ArchiveAggregate,
    projects: Projects,
    archiveDownload: ArchiveDownload,
    sourceDecoder: JsonLdSourceDecoder[ArchiveRejection, ArchiveValue],
    cfg: ArchivePluginConfig
)(implicit uuidF: UUIDF, rcr: RemoteContextResolution) {

  /**
    * Creates an archive with a system generated id.
    *
    * @param project the archive parent project
    * @param value   the archive value
    * @param subject the subject that initiated the action
    */
  def create(
      project: ProjectRef,
      value: ArchiveValue
  )(implicit subject: Subject): IO[ArchiveRejection, ArchiveResource] =
    uuidF().flatMap(uuid => create(uuid.toString, project, value))

  /**
    * Creates an archive with a specific id.
    *
    * @param id      the archive identifier
    * @param project the archive parent project
    * @param value   the archive value
    * @param subject the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      value: ArchiveValue
  )(implicit subject: Subject): IO[ArchiveRejection, ArchiveResource] =
    (for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(CreateArchive(iri, project, value, subject), p)
    } yield res).named("createArchive", moduleType)

  /**
    * Creates an archive from a json-ld representation. If an id is detected in the source document it will be used.
    * Alternately, an id is generated by the system.
    *
    * @param project the archive parent project
    * @param source  the archive json representation
    * @param subject the subject that initiated the action
    */
  def create(project: ProjectRef, source: Json)(implicit subject: Subject): IO[ArchiveRejection, ArchiveResource] =
    (for {
      p            <- projects.fetchActiveProject(project)
      (iri, value) <- sourceDecoder(p, source)
      res          <- eval(CreateArchive(iri, project, value, subject), p)
    } yield res).named("createArchive", moduleType)

  /**
    * Creates an archive from a json-ld representation with a user specified id. If an id is also detected in the source
    * document it will be compared with the specified id. If the user specified id does not match the detected source
    * id, the call will be rejected.
    *
    * @param id      the archive identifier
    * @param project the archive parent project
    * @param source  the archive json representation
    * @param subject the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ArchiveRejection, ArchiveResource] =
    (for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      value <- sourceDecoder(p, iri, source)
      res   <- eval(CreateArchive(iri, project, value, subject), p)
    } yield res).named("createArchive", moduleType)

  /**
    * Fetches an existing archive.
    *
    * @param id      the archive identifier
    * @param project the archive parent project
    */
  def fetch(id: IdSegment, project: ProjectRef): IO[ArchiveRejection, ArchiveResource] =
    (for {
      p     <- projects.fetchProject(project)
      iri   <- expandIri(id, p)
      state <- currentState(project, iri)
      res   <- IO.fromOption(state.toResource(p.apiMappings, p.base, cfg.ttl), ArchiveNotFound(iri, project))
    } yield res).named("fetchArchive", moduleType)

  /**
    * Provides an [[AkkaSource]] for streaming an archive content.
    *
    * @param id             the archive identifier
    * @param project        the archive parent project
    * @param ignoreNotFound ignore resource and file references that do not exist or reject
    */
  def download(
      id: IdSegment,
      project: ProjectRef,
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[ArchiveRejection, AkkaSource] =
    (for {
      resource <- fetch(id, project)
      value     = resource.value
      source   <- archiveDownload(value.value, project, ignoreNotFound)
    } yield source).named("downloadArchive", moduleType)

  private def eval(cmd: CreateArchive, project: Project): IO[ArchiveRejection, ArchiveResource] =
    for {
      result    <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base) = project.apiMappings -> project.base
      resource  <- IO.fromOption(result.state.toResource(am, base, cfg.ttl), UnexpectedInitialState(cmd.id, project.ref))
    } yield resource

  private def currentState(project: ProjectRef, iri: Iri): UIO[ArchiveState] =
    aggregate.state(identifier(project, iri)).named("currentState", moduleType)

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

}

object Archives {

  /**
    * The archive module type.
    */
  final val moduleType: String = "archive"

  /**
    * Iri expansion logic for archives.
    */
  final val expandIri: ExpandIri[InvalidArchiveId] = new ExpandIri(InvalidArchiveId.apply)

  /**
    * The default archive API mappings
    */
  val mappings: ApiMappings = ApiMappings("archive" -> schema.original)

  /**
    * Constructs a new [[Archives]] module instance.
    */
  final def apply(
      projects: Projects,
      archiveDownload: ArchiveDownload,
      cfg: ArchivePluginConfig,
      resourceIdCheck: ResourceIdCheck
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, rcr: RemoteContextResolution, clock: Clock[UIO]): UIO[Archives] = {
    val idAvailability: IdAvailability[ResourceAlreadyExists] = (project, id) =>
      resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))
    apply(projects, archiveDownload, cfg, idAvailability)
  }

  private[archive] def apply(
      projects: Projects,
      archiveDownload: ArchiveDownload,
      cfg: ArchivePluginConfig,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, rcr: RemoteContextResolution, clock: Clock[UIO]): UIO[Archives] = {
    val aggregate = ShardedAggregate.transientSharded(
      definition = TransientEventDefinition(
        entityType = moduleType,
        initialState = ArchiveState.Initial,
        next = next,
        evaluate = evaluate(idAvailability),
        stopStrategy = cfg.aggregate.stopStrategy.transientStrategy
      ),
      config = cfg.aggregate.processor
      // TODO: configure the number of shards
    )
    aggregate.map { agg =>
      new Archives(agg, projects, archiveDownload, sourceDecoder, cfg)
    }
  }

  private[archive] def sourceDecoder(implicit uuidF: UUIDF): JsonLdSourceDecoder[ArchiveRejection, ArchiveValue] =
    new JsonLdSourceDecoder[ArchiveRejection, ArchiveValue](contexts.archives, uuidF)

  private[archive] def next(@unused state: ArchiveState, event: ArchiveCreated): ArchiveState =
    Current(
      id = event.id,
      project = event.project,
      value = event.value,
      createdAt = event.instant,
      createdBy = event.subject
    )

  private[archive] def evaluate(idAvailability: IdAvailability[ResourceAlreadyExists])(
      state: ArchiveState,
      command: CreateArchive
  )(implicit clock: Clock[UIO]): IO[ArchiveRejection, ArchiveCreated] =
    state match {
      case Initial    =>
        idAvailability(command.project, command.id) >>
          IOUtils.instant.map { instant =>
            ArchiveCreated(command.id, command.project, command.value, instant, command.subject)
          }
      case _: Current =>
        IO.raiseError(ResourceAlreadyExists(command.id, command.project))
    }

}
