package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{SchemaCommand, SchemaEvent, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.SchemasDummy.SchemaJournal
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class SchemasDummy private (
    journal: SchemaJournal,
    orgs: Organizations,
    projects: Projects,
    schemaImports: SchemaImports,
    semaphore: IOSemaphore,
    sourceParser: JsonLdSourceResolvingParser[SchemaRejection],
    idAvailability: IdAvailability[ResourceAlreadyExists]
)(implicit clock: Clock[UIO])
    extends Schemas {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] =
    for {
      project                    <- projects.fetchProject(projectRef, notDeprecatedWithQuotas)
      (iri, compacted, expanded) <- sourceParser(project, source)
      expandedResolved           <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                        <- eval(CreateSchema(iri, projectRef, source, compacted, expandedResolved, caller.subject), project)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] =
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedWithQuotas)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      expandedResolved      <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                   <- eval(CreateSchema(iri, projectRef, source, compacted, expandedResolved, caller.subject), project)
    } yield res

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] =
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedWithEventQuotas)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      expandedResolved      <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                   <- eval(UpdateSchema(iri, projectRef, source, compacted, expandedResolved, rev, caller.subject), project)
    } yield res

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project <- projects.fetchProject(projectRef, notDeprecatedWithEventQuotas)
      iri     <- expandIri(id, project)
      res     <- eval(TagSchema(iri, projectRef, tagRev, tag, rev, caller), project)
    } yield res

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project <- projects.fetchProject(projectRef, notDeprecatedWithEventQuotas)
      iri     <- expandIri(id, project)
      res     <- eval(DeprecateSchema(iri, projectRef, rev, caller), project)
    } yield res

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[SchemaFetchRejection, SchemaResource] =
    id.asTag.fold(
      for {
        project <- projects.fetchProject(projectRef)
        iri     <- expandIri(id.value, project)
        state   <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
        res     <- IO.fromOption(state.toResource(project.apiMappings, project.base), SchemaNotFound(iri, projectRef))
      } yield res
    )(fetchBy(_, projectRef))

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(journal.events(offset).filter(e => e.event.project == projectRef))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[SchemaEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(journal.events(offset).filter(e => e.event.project.organization == organization))

  override def events(offset: Offset): Stream[Task, Envelope[SchemaEvent]] =
    journal.events(offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[SchemaFetchRejection, SchemaState] =
    journal.currentState((projectRef, iri), Initial, Schemas.next).map(_.getOrElse(Initial))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, SchemaState] =
    journal.stateAt((projectRef, iri), rev, Initial, Schemas.next, RevisionNotFound.apply).map(_.getOrElse(Initial))

  private def eval(cmd: SchemaCommand, project: Project): IO[SchemaRejection, SchemaResource] =
    semaphore.withPermit {
      for {
        state     <- currentState(cmd.project, cmd.id)
        event     <- Schemas.evaluate(idAvailability)(state, cmd)
        _         <- journal.add(event)
        (am, base) = project.apiMappings -> project.base
        res       <- IO.fromOption(Schemas.next(state, event).toResource(am, base), UnexpectedInitialState(cmd.id))
      } yield res
    }
}

object SchemasDummy {

  type SchemaIdentifier = (ProjectRef, Iri)

  type SchemaJournal = Journal[SchemaIdentifier, SchemaEvent]

  implicit private val eventLens: Lens[SchemaEvent, SchemaIdentifier] =
    (event: SchemaEvent) => (event.project, event.id)

  /**
    * Creates a schema dummy instance
    *
    * @param orgs              the organizations operations bundle
    * @param projects          the projects operations bundle
    * @param schemaImports     resolves the OWL imports from a Schema
    * @param contextResolution the context resolver
    * @param idAvailability    checks if an id is available upon creation
    */
  def apply(
      orgs: Organizations,
      projects: Projects,
      schemaImports: SchemaImports,
      contextResolution: ResolverContextResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit clock: Clock[UIO], uuidF: UUIDF): UIO[SchemasDummy] =
    for {
      journal <- Journal(moduleType, 1L, EventTags.forProjectScopedEvent[SchemaEvent](Schemas.moduleType))
      sem     <- IOSemaphore(1L)
    } yield new SchemasDummy(
      journal,
      orgs,
      projects,
      schemaImports,
      sem,
      new JsonLdSourceResolvingParser[SchemaRejection](
        List(contexts.shacl, contexts.schemasMetadata),
        contextResolution,
        uuidF
      ),
      idAvailability
    )

}
