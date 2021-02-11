package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventExchange.StateExchange
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF, TagLabel}
import io.circe.Json
import monix.bio.UIO

import scala.reflect.ClassTag

/**
  * Resolver which allows to fetch the latest state for an [[Event]].
  */
trait EventExchange {

  type E <: Event
  type State

  protected def cast: ClassTag[E]
  protected def state(event: E, tag: Option[TagLabel]): UIO[Option[ResourceF[State]]]
  protected def toExpanded(state: State): Option[ExpandedJsonLd]
  protected def toSource(state: State): Option[Json]

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
  def toState(event: Event, tag: Option[TagLabel] = None): UIO[Option[StateExchange[State]]] =
    cast.unapply(event) match {
      case Some(e) =>
        state(e, tag).map(_.map(new StateExchange(_, toExpanded, toSource)))
      case None    =>
        UIO.pure(None)
    }
}

object EventExchange {

  final class StateExchange[A](
      state: ResourceF[A],
      toExpandedState: A => Option[ExpandedJsonLd],
      toSourceState: A => Option[Json]
  ) {
    def toExpanded: Option[ResourceF[ExpandedJsonLd]] =
      toExpandedState(state.value).map(state.as)

    def toGraph: Option[ResourceF[Graph]] =
      toExpanded.flatMap(_.value.toGraph.toOption).map(state.as)

    def toSource: Option[ResourceF[Json]] =
      toSourceState(state.value).map(state.as)

  }
}
