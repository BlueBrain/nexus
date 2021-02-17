package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventExchange.StateExchange
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF, TagLabel}
import io.circe.{Encoder, Json}
import monix.bio.{IO, Task, UIO}

import scala.reflect.ClassTag

/**
  * Resolver which allows to fetch the latest state for an [[Event]].
  */
trait EventExchange {

  type E <: Event
  type State

  protected def cast: ClassTag[E]
  protected def fetch(event: E, tag: Option[TagLabel]): IO[EventFetchRejection, ResourceF[State]]
  protected def toExpanded(state: State): IO[RdfError, ExpandedJsonLd]
  protected def toSource(state: State): UIO[Json]

  /**
    * Checks if this [[EventExchange]] applies to provided [[Event]]
    * @param event  the event to check
    */
  def appliesTo(event: Event): Boolean = cast.unapply(event).isDefined

  /**
    * Fetches latest ''State'' for this event.
    *
    * @param event the event from where to compute the state
    */
  def toState(event: Event, tag: Option[TagLabel] = None): Task[StateExchange[State]] =
    cast.unapply(event) match {
      case Some(e) =>
        fetch(e, tag).map { s => new StateExchange(s, toExpanded(s.value), toSource(s.value)) }
      case None    =>
        Task.raiseError(new IllegalArgumentException(""))
    }
}

object EventExchange {

  final class StateExchange[A](
      val state: ResourceF[A],
      toExpandedState: IO[RdfError, ExpandedJsonLd],
      toSourceState: UIO[Json]
  ) {
    def toExpanded: IO[RdfError, ResourceF[ExpandedJsonLd]] =
      toExpandedState.map(state.as)

    def toGraph: IO[RdfError, ResourceF[Graph]] =
      toExpanded.flatMap { e => IO.fromEither(e.value.toGraph) }.map(state.as)

    def toSource: UIO[ResourceF[Json]] =
      toSourceState.map(state.as)

  }

  def create[F <: Event, Rejection: Encoder, S](
      fetchLatest: F => IO[Rejection, ResourceF[S]],
      fetchByTag: (F, TagLabel) => IO[Rejection, ResourceF[S]],
      asExpanded: S => IO[RdfError, ExpandedJsonLd],
      asSource: S => UIO[Json]
  )(implicit c: ClassTag[F]): EventExchange = new EventExchange {
    override type E     = F
    override type State = S

    override protected def cast: ClassTag[E] = c

    override protected def fetch(
        event: F,
        tag: Option[TagLabel]
    ): IO[EventFetchRejection, ResourceF[S]] = {
      val f = tag match {
        case Some(t) => fetchByTag(event, t)
        case None    => fetchLatest(event)
      }
      f.mapError(EventFetchRejection(_))
    }

    override protected def toExpanded(state: State): IO[RdfError, ExpandedJsonLd] = asExpanded(state)

    override protected def toSource(state: State): UIO[Json] = asSource(state)
  }
}
