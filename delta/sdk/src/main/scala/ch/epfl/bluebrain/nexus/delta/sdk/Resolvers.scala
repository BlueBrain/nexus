package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{CrossProjectCurrent, Current, InProjectCurrent, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Operations for handling resolvers
  */
trait Resolvers {

  /**
    * Create a new resolver where the id is either present on the payload or self generated
    * @param project        the project where the resolver will belong
    * @param resolverFields the payload to create the resolver
    */
  def create(project: ProjectRef, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Create a new resolver with the provided id
    * @param id             the resolver identifier
    * @param project        the project where the resolver will belong
    * @param resolverFields the payload to create the resolver
    */
  def create(id: Iri, project: ProjectRef, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Update an existing resolver
    * @param id             the resolver identifier
    * @param project        the project where the resolver will belong
    * @param rev            the current revision of the resolver
    * @param resolverFields the payload to update the resolver
    */
  def update(id: Iri, project: ProjectRef, rev: Long, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Deprecate an existing resolver
    * @param id      the resolver identifier
    * @param project the project where the resolver will belong
    * @param rev     the current revision of the resolver
    */
  def deprecate(id: Iri, project: ProjectRef, rev: Long)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Fetch the last version of a resolver
    * @param id      the resolver identifier
    * @param project the project where the resolver will belong
    */
  def fetch(id: Iri, project: ProjectRef): UIO[Option[ResolverResource]]

  /**
    * Fetches the resolver at a given revision
    * @param id      the resolver identifier
    * @param project the project where the resolver will belong
    * @param rev     the current revision of the resolver
    */
  def fetchAt(id: Iri, project: ProjectRef, rev: Long): IO[RevisionNotFound, Option[ResolverResource]]

  /**
    * A non terminating stream of events for resolvers. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[ResolverEvent]]

  /**
    * The current resolver events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[ResolverEvent]]

}

object Resolvers {

  /**
    * The resolvers module type.
    */
  final val moduleType: String = "resolver"

  private[delta] val inProjectType    = "in-project"
  private[delta] val crossProjectType = "cross-project"

  import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
  import io.scalaland.chimney.dsl._

  private[delta] def next(state: ResolverState, event: ResolverEvent): ResolverState = {
    (state, event) match {
      // Creating an in-project resolver
      case (Initial, e: InProjectResolverCreated)                           =>
        e.into[InProjectCurrent]
          .withFieldConst(_.deprecated, false)
          .withFieldConst(_.createdAt, e.instant)
          .withFieldConst(_.createdBy, e.subject)
          .withFieldConst(_.updatedAt, e.instant)
          .withFieldConst(_.updatedBy, e.subject)
          .transform
      // Updating an in-project resolver
      case (c: InProjectCurrent, e: InProjectResolverUpdated)               =>
        c.copy(priority = e.priority, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)

      // Creating a cross-project resolver
      case (Initial, e: CrossProjectResolverCreated)                        =>
        e.into[CrossProjectCurrent]
          .withFieldConst(_.deprecated, false)
          .withFieldConst(_.createdAt, e.instant)
          .withFieldConst(_.createdBy, e.subject)
          .withFieldConst(_.updatedAt, e.instant)
          .withFieldConst(_.updatedBy, e.subject)
          .transform
      // Updating a cross-project resolver
      case (c: CrossProjectCurrent, e: CrossProjectResolverUpdated)         =>
        c.copy(
          priority = e.priority,
          resourceTypes = e.resourceTypes,
          projects = e.projects,
          identities = e.identities,
          rev = e.rev,
          updatedAt = e.instant,
          updatedBy = e.subject
        )

      // Deprecating a in-project resolver
      case (c: InProjectCurrent, e: ResolverDeprecated) if !c.deprecated    =>
        c.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
      // Deprecating a cross-project resolver
      case (c: CrossProjectCurrent, e: ResolverDeprecated) if !c.deprecated =>
        c.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)

      // Cases that should happen and that should have been filtered out by the evaluation
      case (s, _)                                                           => s
    }
  }

  private[delta] def evaluate(
      isOpen: ProjectRef => IO[ReadOnlyProject, Unit]
  )(state: ResolverState, command: ResolverCommand)(implicit
      caller: Caller,
      clock: Clock[UIO]
  ): IO[ResolverRejection, ResolverEvent] = {

    // All provided identities must be part of the caller identities
    def validateIdentities(identities: Set[Identity]): IO[ResolverRejection, Unit] = {
      if (identities.isEmpty)
        IO.raiseError(NoIdentities)
      else {
        val missing = identities.diff(caller.identities)
        if (missing.isEmpty) {
          IO.unit
        } else {
          IO.raiseError(InvalidIdentities(missing))
        }
      }
    }

    val stateMachine = (state, command) match {
      /**
        * Creating a resolver
        */
      // The resolver already exists
      case (_: Current, c: CreateResolver)                         =>
        IO.raiseError(ResolverAlreadyExists(c.id, c.project))
      // Create a in-project
      case (Initial, c: CreateInProjectResolver)                   =>
        instant.map { now =>
          c.into[InProjectResolverCreated]
            .withFieldConst(_.rev, 1L)
            .withFieldConst(_.instant, now)
            .withFieldConst(_.subject, caller.subject)
            .transform
        }
      // Create a cross-project resolver
      case (Initial, c: CreateCrossProjectResolver)                =>
        for {
          _   <- validateIdentities(c.identities)
          now <- instant
        } yield c
          .into[CrossProjectResolverCreated]
          .withFieldConst(_.rev, 1L)
          .withFieldConst(_.instant, now)
          .withFieldConst(_.subject, caller.subject)
          .transform

      /**
        * Updating a resolver
        */
      // Update a non existing resolver
      case (Initial, c: UpdateResolver)                            =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision has been provided
      case (s: Current, c: UpdateResolver) if c.rev != s.rev       =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))

      // Update a in-project resolver
      case (s: InProjectCurrent, c: UpdateInProjectResolver)       =>
        instant.map { now =>
          c.into[InProjectResolverUpdated]
            .withFieldConst(_.rev, s.rev + 1)
            .withFieldConst(_.instant, now)
            .withFieldConst(_.subject, caller.subject)
            .transform
        }
      // Update a cross-project resolver
      case (s: CrossProjectCurrent, c: UpdateCrossProjectResolver) =>
        for {
          _   <- validateIdentities(c.identities)
          now <- instant
        } yield c
          .into[CrossProjectResolverUpdated]
          .withFieldConst(_.rev, s.rev + 1)
          .withFieldConst(_.instant, now)
          .withFieldConst(_.subject, caller.subject)
          .transform

      // Attempt to transform an in-project to a cross-project
      case (_: InProjectCurrent, c: UpdateCrossProjectResolver)    =>
        IO.raiseError(DifferentResolverType(c.id, crossProjectType, inProjectType))
      // Attempt to transform a cross-project to an in-project
      case (_: CrossProjectCurrent, c: UpdateInProjectResolver)    =>
        IO.raiseError(DifferentResolverType(c.id, inProjectType, crossProjectType))

      /**
        * Deprecating a resolver
        */
      // Resolver can't be found
      case (Initial, c: DeprecateResolver)                         =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case (s: Current, c: DeprecateResolver) if c.rev != s.rev    =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case (s: Current, _: DeprecateResolver) if s.deprecated      =>
        IO.raiseError(ResolverIsDeprecated(s.id))
      case (s: Current, c: DeprecateResolver)                      =>
        instant.map { now =>
          c.into[ResolverDeprecated]
            .withFieldConst(_.rev, s.rev + 1)
            .withFieldConst(_.instant, now)
            .withFieldConst(_.subject, caller.subject)
            .transform
        }

    }

    isOpen(command.project) >> stateMachine
  }

}
