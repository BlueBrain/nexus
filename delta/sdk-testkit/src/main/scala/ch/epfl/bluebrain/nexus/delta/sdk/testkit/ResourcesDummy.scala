package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy.ResourcesJournal
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Resources implementation
  *
  * @param journal     the journal to store events
  * @param fetchSchema a function to retrieve the schema based on the schema iri
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class ResourcesDummy private (
    journal: ResourcesJournal,
    fetchSchema: ResourceRef => UIO[Option[SchemaResource]],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution)
    extends Resources {

  override def create(
      project: Project,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    sourceAsJsonLD(project, source).flatMap { case (id, compacted, expanded) =>
      eval(CreateResource(id, project.ref, schema, source, compacted, expanded, caller))
    }

  override def create(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    sourceAsJsonLD(id, source).flatMap { case (compacted, expanded) =>
      eval(CreateResource(id, project, schema, source, compacted, expanded, caller))
    }

  override def update(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    sourceAsJsonLD(id, source).flatMap { case (compacted, expanded) =>
      eval(UpdateResource(id, project, schemaOpt, source, compacted, expanded, rev, caller))
    }

  override def tag(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    eval(TagResource(id, project, schemaOpt, tagRev, tag, rev, caller))

  override def deprecate(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    eval(DeprecateResource(id, project, schemaOpt, rev, caller))

  override def fetch(id: Iri, project: ProjectRef, schemaOpt: Option[ResourceRef]): UIO[Option[DataResource]] =
    journal
      .currentState((project, id), Initial, Resources.next)
      .map(_.flatMap(_.toResource))
      .map(validateSameSchema(_, schemaOpt))

  override def fetchAt(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  ): IO[RevisionNotFound, Option[DataResource]] =
    journal
      .stateAt((project, id), rev, Initial, Resources.next, RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))
      .map(validateSameSchema(_, schemaOpt))

  private def eval(cmd: ResourceCommand): IO[ResourceRejection, DataResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState((cmd.project, cmd.id), Initial, Resources.next).map(_.getOrElse(Initial))
        event <- Resources.evaluate(fetchSchema)(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Resources.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.id)))
      } yield res
    }

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    journal.currentEvents(offset)

  private def sourceAsJsonLD(
      project: Project,
      source: Json
  ): IO[ResourceRejection, (Iri, CompactedJsonLd, ExpandedJsonLd)] =
    for {
      expandedNoId <- JsonLd.expand(source).leftMap(err => InvalidJsonLdFormat(None, err))
      id           <- expandedNoId.rootId.asIri.fold(uuidF().map(uuid => project.base / uuid.toString))(IO.pure)
      expanded      = expandedNoId.replaceId(id)
      compacted    <- expanded.toCompacted(source).leftMap(err => InvalidJsonLdFormat(Some(id), err))
    } yield (id, compacted, expanded)

  private def sourceAsJsonLD(id: Iri, source: Json): IO[ResourceRejection, (CompactedJsonLd, ExpandedJsonLd)] =
    for {
      expandedNoId <- JsonLd.expand(source).leftMap(err => InvalidJsonLdFormat(Some(id), err))
      id           <- expandedNoId.rootId.asIri match {
                        case Some(sourceId) if sourceId != id => IO.raiseError(UnexpectedResourceId(id, sourceId))
                        case _                                => IO.pure(id)
                      }
      expanded      = expandedNoId.replaceId(id)
      compacted    <- expanded.toCompacted(source).leftMap(err => InvalidJsonLdFormat(Some(id), err))
    } yield (compacted, expanded)

  private def validateSameSchema(
      resourceOpt: Option[DataResource],
      schemaOpt: Option[ResourceRef]
  ): Option[DataResource] =
    resourceOpt match {
      case Some(value) if schemaOpt.forall(_ == value.schema) => Some(value)
      case _                                                  => None
    }
}

object ResourcesDummy {

  type ResourceIdentifier = (ProjectRef, Iri)

  type ResourcesJournal = Journal[ResourceIdentifier, ResourceEvent]

  implicit private val eventLens: Lens[ResourceEvent, ResourceIdentifier] =
    (event: ResourceEvent) => (event.project, event.id)

  /**
    * Creates a resources dummy instance
    *
    * @param fetchSchema a function to retrieve the schema based on the schema iri
    */
  def apply(
      fetchSchema: ResourceRef => UIO[Option[SchemaResource]]
  )(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution): UIO[ResourcesDummy] =
    for {
      journal <- Journal(moduleType)
      sem     <- IOSemaphore(1L)
    } yield new ResourcesDummy(journal, fetchSchema, sem)

}
