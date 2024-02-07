package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy

import cats.effect.IO
import cats.effect.kernel.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.TypeHierarchyResource
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchyCommand.{CreateTypeHierarchy, UpdateTypeHierarchy}
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchyEvent.{TypeHierarchyCreated, TypeHierarchyUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchyRejection.{IncorrectRev, RevisionNotFound, TypeHierarchyAlreadyExists, TypeHierarchyDoesNotExist}
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.{TypeHierarchyCommand, TypeHierarchyEvent, TypeHierarchyRejection, TypeHierarchyState}
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.{GlobalEntityDefinition, GlobalEventLog, StateMachine, Transactors}

trait TypeHierarchy {

  /** Creates a type hierarchy with the provided mapping. */
  def create(mapping: TypeHierarchyMapping)(implicit subject: Subject): IO[TypeHierarchyResource]

  /** Updates the type hierarchy with the provided mapping at the provided revision. */
  def update(mapping: TypeHierarchyMapping, rev: Int)(implicit subject: Subject): IO[TypeHierarchyResource]

  /** Fetches the current type hierarchy. */
  def fetch: IO[TypeHierarchyResource]

  /** Fetches the type hierarchy at the provided revision. */
  def fetch(rev: Int): IO[TypeHierarchyResource]

}

object TypeHierarchy {

  final val entityType: EntityType = EntityType("type-hierarchy")
  final val id: Iri                = nxv.TypeHierarchy

  private type TypeHierarchyLog =
    GlobalEventLog[Iri, TypeHierarchyState, TypeHierarchyCommand, TypeHierarchyEvent, TypeHierarchyRejection]

  def apply(xas: Transactors, config: TypeHierarchyConfig, clock: Clock[IO]): TypeHierarchy =
    apply(GlobalEventLog(definition(clock), config.eventLog, xas))

  def apply(log: TypeHierarchyLog): TypeHierarchy = new TypeHierarchy {
    override def create(mapping: TypeHierarchyMapping)(implicit subject: Subject): IO[TypeHierarchyResource] =
      eval(CreateTypeHierarchy(mapping, subject))

    override def update(mapping: TypeHierarchyMapping, rev: Int)(implicit subject: Subject): IO[TypeHierarchyResource] =
      eval(UpdateTypeHierarchy(mapping, rev, subject))

    override def fetch: IO[TypeHierarchyResource] =
      log.stateOr(id, TypeHierarchyDoesNotExist).map(_.toResource)

    override def fetch(rev: Int): IO[TypeHierarchyResource] =
      log.stateOr(id, rev, TypeHierarchyDoesNotExist, RevisionNotFound).map(_.toResource)

    private def eval(cmd: TypeHierarchyCommand): IO[TypeHierarchyResource] =
      log.evaluate(id, cmd).map(_._2.toResource)
  }

  private def evaluate(
      clock: Clock[IO]
  )(state: Option[TypeHierarchyState], command: TypeHierarchyCommand): IO[TypeHierarchyEvent] = {

    def create(c: CreateTypeHierarchy) =
      state match {
        case None => clock.realTimeInstant.map(TypeHierarchyCreated(id, c.mapping, 1, _, c.subject))
        case _    => IO.raiseError(TypeHierarchyAlreadyExists)
      }

    def update(c: UpdateTypeHierarchy) =
      state match {
        case None                      => IO.raiseError(TypeHierarchyDoesNotExist)
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s)                   =>
          clock.realTimeInstant.map(TypeHierarchyUpdated(id, c.mapping, s.rev + 1, _, c.subject))
      }

    command match {
      case c: CreateTypeHierarchy => create(c)
      case u: UpdateTypeHierarchy => update(u)
    }
  }

  private def next(state: Option[TypeHierarchyState], ev: TypeHierarchyEvent): Option[TypeHierarchyState] =
    (state, ev) match {
      case (None, TypeHierarchyCreated(id, mapping, _, instant, subject))     =>
        Some(TypeHierarchyState(id, mapping, 1, deprecated = false, instant, subject, instant, subject))
      case (Some(s), TypeHierarchyUpdated(_, mapping, rev, instant, subject)) =>
        Some(s.copy(mapping = mapping, rev = rev, updatedAt = instant, updatedBy = subject))
      case (Some(_), TypeHierarchyCreated(_, _, _, _, _))                     => None
      case (None, _)                                                          => None
    }

  def definition(
      clock: Clock[IO]
  ): GlobalEntityDefinition[
    Iri,
    TypeHierarchyState,
    TypeHierarchyCommand,
    TypeHierarchyEvent,
    TypeHierarchyRejection
  ] =
    GlobalEntityDefinition(
      entityType,
      StateMachine(
        None,
        evaluate(clock),
        next
      ),
      TypeHierarchyEvent.serializer,
      TypeHierarchyState.serializer,
      onUniqueViolation = (_: Iri, c: TypeHierarchyCommand) =>
        c match {
          case _: CreateTypeHierarchy => TypeHierarchyAlreadyExists
          case u: UpdateTypeHierarchy => IncorrectRev(u.rev, u.rev + 1)
        }
    )

}
