package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.CrossProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
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
    * Add a tag to an existing resolver
    *
    * @param id        the resolver identifier
    * @param project   the project where the resolver belongs
    * @param tag       the tag name
    * @param tagRev    the tag revision
    * @param rev       the current revision of the resolver
    */
  def tag(id: Iri, project: ProjectRef, tag: Label, tagRev: Long, rev: Long)(implicit
      caller: Subject
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Deprecate an existing resolver
    * @param id      the resolver identifier
    * @param project the project where the resolver belongs
    * @param rev     the current revision of the resolver
    */
  def deprecate(id: Iri, project: ProjectRef, rev: Long)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Fetch the last version of a resolver
    * @param id      the resolver identifier
    * @param project the project where the resolver belongs
    */
  def fetch(id: Iri, project: ProjectRef): UIO[Option[ResolverResource]]

  /**
    * Fetches the resolver at a given revision
    * @param id      the resolver identifier
    * @param project the project where the resolver belongs
    * @param rev     the current revision of the resolver
    */
  def fetchAt(id: Iri, project: ProjectRef, rev: Long): IO[RevisionNotFound, Option[ResolverResource]]

  /**
    * Fetches a resolver by tag.
    *
    * @param id        the resolver identifier
    * @param project   the project where the resolver belongs
    * @param tag       the tag revision
    */
  def fetchBy(
      id: Iri,
      project: ProjectRef,
      tag: Label
  ): IO[ResolverRejection, Option[ResolverResource]]

  /**
    * A non terminating stream of events for resolvers. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[ResolverEvent]]

}

object Resolvers {

  /**
    * The resolvers module type.
    */
  final val moduleType: String = "resolver"

  import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant

  private[delta] def next(state: ResolverState, event: ResolverEvent): ResolverState = {
    (state, event) match {
      // Creating a resolver
      case (Initial, e: ResolverCreated)                                  =>
        Current(
          id = e.id,
          project = e.project,
          value = e.value,
          tags = Map.empty,
          rev = e.rev,
          deprecated = false,
          createdAt = e.instant,
          createdBy = e.subject,
          updatedAt = e.instant,
          updatedBy = e.subject
        )
      // Updating a resolver
      case (c: Current, e: ResolverUpdated) if c.value.tpe == e.value.tpe =>
        c.copy(
          value = e.value,
          rev = e.rev,
          updatedAt = e.instant,
          updatedBy = e.subject
        )

      // Tagging a resolver
      case (c: Current, e: ResolverTagAdded)                              =>
        c.copy(rev = e.rev, tags = c.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)

      // Deprecating a resolver
      case (c: Current, e: ResolverDeprecated)                            =>
        c.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)

      // Cases that should happen and that should have been filtered out by the evaluation
      case _                                                              => state
    }
  }

  private[delta] def evaluate(state: ResolverState, command: ResolverCommand)(implicit
      caller: Caller,
      clock: Clock[UIO]
  ): IO[ResolverRejection, ResolverEvent] = {

    def validateResolverValue(value: ResolverValue): IO[ResolverRejection, Unit] =
      value match {
        case CrossProjectValue(_, _, _, identities) =>
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
        case _                                      => IO.unit
      }

    (state, command) match {
      /**
        * Creating a resolver
        */
      // The resolver already exists
      case (_: Current, c: CreateResolver)                                        =>
        IO.raiseError(ResolverAlreadyExists(c.id, c.project))
      // Create a resolver
      case (Initial, c: CreateResolver)                                           =>
        for {
          _   <- validateResolverValue(c.value)
          now <- instant
        } yield ResolverCreated(
          id = c.id,
          project = c.project,
          value = c.value,
          rev = 1L,
          instant = now,
          subject = caller.subject
        )

      /**
        * Updating a resolver
        */
      // Update a non existing resolver
      case (Initial, c: UpdateResolver)                                           =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision has been provided
      case (s: Current, c: UpdateResolver) if c.rev != s.rev                      =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Resolver has been deprecated
      case (s: Current, _: UpdateResolver) if s.deprecated                        =>
        IO.raiseError(ResolverIsDeprecated(s.id))

      // Update a resolver
      case (s: Current, c: UpdateResolver)                                        =>
        for {
          _   <- if (s.value.tpe == c.value.tpe) IO.unit
                 else IO.raiseError(DifferentResolverType(c.id, c.value.tpe, s.value.tpe))
          _   <- validateResolverValue(c.value)
          now <- instant
        } yield ResolverUpdated(
          id = c.id,
          project = c.project,
          value = c.value,
          rev = s.rev + 1,
          instant = now,
          subject = caller.subject
        )

      /**
        * Tagging a resolver
        */
      // Resolver can't be found
      case (Initial, c: TagResolver)                                              =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case (s: Current, c: TagResolver) if c.rev != s.rev                         =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Revision to tag is invalid
      case (s: Current, c: TagResolver) if c.targetRev < 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      // State is deprecated
      case (s: Current, _: TagResolver) if s.deprecated                           =>
        IO.raiseError(ResolverIsDeprecated(s.id))
      case (s: Current, c: TagResolver)                                           =>
        instant.map { now =>
          ResolverTagAdded(
            id = c.id,
            project = c.project,
            targetRev = c.targetRev,
            tag = c.tag,
            rev = s.rev + 1,
            instant = now,
            subject = caller.subject
          )
        }

      /**
        * Deprecating a resolver
        */
      // Resolver can't be found
      case (Initial, c: DeprecateResolver)                                        =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case (s: Current, c: DeprecateResolver) if c.rev != s.rev                   =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case (s: Current, _: DeprecateResolver) if s.deprecated                     =>
        IO.raiseError(ResolverIsDeprecated(s.id))
      case (s: Current, c: DeprecateResolver)                                     =>
        instant.map { now =>
          ResolverDeprecated(
            id = c.id,
            project = c.project,
            rev = s.rev + 1,
            instant = now,
            subject = caller.subject
          )
        }

    }
  }

}
