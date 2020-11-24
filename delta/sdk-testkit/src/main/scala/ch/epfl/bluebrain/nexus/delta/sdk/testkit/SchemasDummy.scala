package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser.expandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{SchemaCommand, SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.SchemasDummy.SchemaJournal
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Schemas implementation
  *
  * @param journal     the journal to store events
  * @param orgs        the organizations operations bundle
  * @param projects    the projects operations bundle
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class SchemasDummy private (
    journal: SchemaJournal,
    orgs: Organizations,
    projects: Projects,
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution)
    extends Schemas {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project                    <- projects.fetchActiveProject(projectRef)
      (iri, compacted, expanded) <- JsonLdSourceParser.asJsonLd(project, source)
      res                        <- eval(CreateSchema(iri, projectRef, source, compacted, expanded, caller), project)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- JsonLdSourceParser.asJsonLd(project, iri, source)
      res                   <- eval(CreateSchema(iri, projectRef, source, compacted, expanded, caller), project)
    } yield res

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- JsonLdSourceParser.asJsonLd(project, iri, source)
      res                   <- eval(UpdateSchema(iri, projectRef, source, compacted, expanded, rev, caller), project)
    } yield res

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(TagSchema(iri, projectRef, tagRev, tag, rev, caller), project)
    } yield res

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(DeprecateSchema(iri, projectRef, rev, caller), project)
    } yield res

  override def fetch(id: IdSegment, projectRef: ProjectRef): IO[SchemaRejection, Option[SchemaResource]] =
    for {
      project <- projects.fetchProject(projectRef)
      iri     <- expandIri(id, project)
      state   <- currentState(projectRef, iri)
    } yield state.toResource(project.apiMappings, project.base)

  override def fetchAt(id: IdSegment, projectRef: ProjectRef, rev: Long): IO[SchemaRejection, Option[SchemaResource]] =
    for {
      project <- projects.fetchProject(projectRef)
      iri     <- expandIri(id, project)
      state   <- stateAt(projectRef, iri, rev)
    } yield state.toResource(project.apiMappings, project.base)

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

  private def currentState(projectRef: ProjectRef, iri: Iri) =
    journal.currentState((projectRef, iri), Initial, Schemas.next).map(_.getOrElse(Initial))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    journal.stateAt((projectRef, iri), rev, Initial, Schemas.next, RevisionNotFound.apply).map(_.getOrElse(Initial))

  private def eval(cmd: SchemaCommand, project: Project): IO[SchemaRejection, SchemaResource] =
    semaphore.withPermit {
      for {
        state     <- currentState(cmd.project, cmd.id)
        event     <- Schemas.evaluate(state, cmd)
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
    * @param orgs        the organizations operations bundle
    * @param projects    the projects operations bundle
    */
  def apply(
      orgs: Organizations,
      projects: Projects
  )(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution): UIO[SchemasDummy] =
    for {
      journal <- Journal(moduleType)
      sem     <- IOSemaphore(1L)
    } yield new SchemasDummy(journal, orgs, projects, sem)

}
