package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand.{CreateOrganization, DeprecateOrganization, UpdateOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{OrganizationCommand, OrganizationRejection, OrganizationState, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.OrganizationsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, Organizations}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Organizations implementation that uses a synchronized in memory journal.
  *
  * @param journal     a ref to the journal containing all the events wrapped in an envelope
  * @param cache       a ref to the cache containing all the current organization resources
  * @param uuidToLabel a ref to the map where the keys are the uuids and the values are the labels
  * @param semaphore   a semaphore for serializing write operations on the journal
  * @param maxStreamSize    truncate event stream after this size
  */
final class OrganizationsDummy private (
    journal: IORef[OrganizationsJournal],
    cache: IORef[OrganizationsCache],
    uuidToLabel: IORef[UUIDToLabel],
    semaphore: IOSemaphore,
    maxStreamSize: Long
)(implicit clock: Clock[UIO], uuidF: UUIDF)
    extends Organizations {

  private val offsetMax = new AtomicLong()

  private def journalMap: UIO[Map[Label, OrganizationsJournal]] =
    journal.get.map { v =>
      v.groupBy(_.event.label).map { case (k, v) =>
        k -> v.sortBy(_.sequenceNr)
      }
    }

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    uuidF().flatMap { uuid =>
      eval(CreateOrganization(label, uuid, description, caller)).flatMap(setUuid)
    }

  override def update(
      label: Label,
      description: Option[String],
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller))

  override def deprecate(
      label: Label,
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller))

  override def fetch(label: Label): UIO[Option[OrganizationResource]] =
    currentState(label).map(_.flatMap(_.toResource))

  override def fetchAt(
      label: Label,
      rev: Long
  ): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] =
    stateAt(label, rev).map(_.flatMap(_.toResource))

  override def fetch(uuid: UUID): UIO[Option[OrganizationResource]] =
    uuidToLabel.get.flatMap(_.get(uuid) match {
      case Some(label) => fetch(label)
      case None        => IO.pure(None)
    })

  override def fetchAt(
      uuid: UUID,
      rev: Long
  ): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] =
    uuidToLabel.get.flatMap(_.get(uuid) match {
      case Some(label) => fetchAt(label, rev)
      case None        => IO.pure(None)
    })

  override def list(
      pagination: Pagination.FromPagination,
      params: OrganizationSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    cache.get.map { resources =>
      val filtered = resources.values.filter(params.matches).toVector.sortBy(_.createdAt)
      UnscoredSearchResults(
        filtered.length.toLong,
        filtered.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }

  override def events(offset: Offset = NoOffset): Stream[Task, Envelope[OrganizationEvent]] =
    DummyHelpers.eventsFromJournal(journal.get, offset, maxStreamSize)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[OrganizationEvent]] =
    DummyHelpers.currentEventsFromJournal(journal.get, offset, maxStreamSize)

  private def setToCache(resource: OrganizationResource): UIO[Unit]              =
    cache.update(_ + (resource.value.label -> resource))

  private def setUuid(resource: OrganizationResource): UIO[OrganizationResource] =
    uuidToLabel.update(_ + (resource.value.uuid -> resource.value.label)).as(resource)

  private def currentState(label: Label): UIO[Option[OrganizationState]]         =
    journalMap.map { labelsEvents =>
      labelsEvents
        .get(label)
        .map(_.foldLeft[OrganizationState](Initial) { (s, e) => Organizations.next(s, e.event) })
    }

  private def stateAt(label: Label, rev: Long): IO[RevisionNotFound, Option[OrganizationState]] =
    journalMap.flatMap { labelsEvents =>
      labelsEvents.get(label).traverse { events =>
        if (events.size < rev)
          IO.raiseError(RevisionNotFound(rev, events.size.toLong))
        else
          events
            .foldLeft[OrganizationState](Initial) {
              case (state, envelope) if envelope.event.rev <= rev => Organizations.next(state, envelope.event)
              case (state, _)                                     => state
            }
            .pure[UIO]
      }
    }

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] =
    semaphore.withPermit {
      for {
        tgEvents <- journal.get
        state    <- currentState(cmd.label).map(_.getOrElse(Initial))
        event    <- Organizations.evaluate(state, cmd)
        _        <- journal.set(tgEvents :+ makeEnvelope(event, s"$entityType-${event.label}", offsetMax))
        res      <- IO.fromEither(Organizations.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
        _        <- setToCache(res)
      } yield res
    }
}

object OrganizationsDummy {

  val entityType: String = "organizations"

  type OrganizationsJournal = Vector[Envelope[OrganizationEvent]]
  type UUIDToLabel          = Map[UUID, Label]
  type OrganizationsCache   = Map[Label, OrganizationResource]

  /**
    * Creates a new dummy Organizations implementation.
    *
    * @param maxStreamSize    truncate event stream after this size
    */
  final def apply(maxStreamSize: Long = Long.MaxValue)(implicit
      uuidF: UUIDF = UUIDF.random,
      clock: Clock[UIO] = IO.clock
  ): UIO[OrganizationsDummy] =
    for {
      journalRef  <- IORef.of[OrganizationsJournal](Vector.empty)
      uuidToLabel <- IORef.of[UUIDToLabel](Map.empty)
      cacheRef    <- IORef.of[OrganizationsCache](Map.empty)
      sem         <- IOSemaphore(1L)
    } yield new OrganizationsDummy(journalRef, cacheRef, uuidToLabel, sem, maxStreamSize)
}
