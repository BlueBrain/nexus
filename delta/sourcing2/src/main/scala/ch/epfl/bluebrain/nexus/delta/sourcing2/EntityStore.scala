package ch.epfl.bluebrain.nexus.delta.sourcing2

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.SourcingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventStore
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateStore
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.TrackStore
import doobie.implicits._
import doobie.util.transactor.Transactor
import monix.bio.{Task, UIO}

trait EntityStore {

  def latestState[State](tpe: EntityType, id: EntityId)(implicit decoder: PayloadDecoder[State]): Task[Option[State]]

  def save[Event, State](
      entitySerializer: EntitySerializer[Event, State],
      tracker: Event => Set[String]
  )(tpe: EntityType, id: EntityId, event: Event, state: State): Task[Unit]
}

object EntityStore {

  final class EntityStoreImpl(
      eventStore: EventStore,
      stateStore: StateStore,
      trackStore: TrackStore,
      xa: Transactor[Task],
      config: SourcingConfig
  )(implicit clock: Clock[UIO])
      extends EntityStore {
    override def latestState[State](tpe: EntityType, id: EntityId)(implicit
        decoder: PayloadDecoder[State]
    ): Task[Option[State]] =
      stateStore.latestState(tpe, id)

    override def save[Event, State](
        entitySerializer: EntitySerializer[Event, State],
        tracker: Event => Set[String]
    )(tpe: EntityType, id: EntityId, event: Event, state: State): Task[Unit] = {
      import entitySerializer._
      for {
        now    <- instant
        tracks <- trackStore.getOrCreate(tracker(event)).map(_.values)
        _      <-
          (
            eventStore.save(eventSerializer.serialize(tpe, id, event, tracks, now, config.deltaVersion)) >>
              stateStore.save(stateSerializer.serialize(tpe, id, state, tracks, Tag.Latest, now, config.deltaVersion))
          ).transact(xa)
      } yield ()
    }
  }

}
