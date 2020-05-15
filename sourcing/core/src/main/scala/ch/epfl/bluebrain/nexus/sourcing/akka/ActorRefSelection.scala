package ch.epfl.bluebrain.nexus.sourcing.akka

import akka.actor.ActorRef
import cats.Applicative
import cats.implicits._

/**
  * An aggregate actor selection strategy.
  *
  * @tparam F the selection effect type
  */
private[akka] trait ActorRefSelection[F[_]] {

  /**
    * Selects an appropriate [[ActorRef]] for a name and identifier.
    *
    * @param name the aggregate name
    * @param id   the entity identifier
    * @return the actor to be used by the aggregate implementation
    */
  def apply(name: String, id: String): F[ActorRef]

}

private[akka] object ActorRefSelection {

  /**
    * Lifts a function with the same signature to an [[ActorRefSelection]].
    *
    * @param f the function used to derive an [[ActorRef]] for the provided name and identifier.
    * @tparam F the effect type
    * @return an actor reference in the __F__ context
    */
  def apply[F[_]](f: (String, String) => F[ActorRef]): ActorRefSelection[F] =
    (name: String, id: String) => f(name, id)

  /**
    * An __ActorRefSelection__ that returns a constant ActorRef. This is useful in situations where there's an implied
    * destination of messages based on the information in the message, for example with cluster sharding.
    *
    * @param ref the constant ActorRef value
    * @tparam F the effect type
    * @return an actor reference in the __F__ context
    */
  def const[F[_]: Applicative](ref: ActorRef): ActorRefSelection[F] =
    apply((_, _) => ref.pure[F])
}
