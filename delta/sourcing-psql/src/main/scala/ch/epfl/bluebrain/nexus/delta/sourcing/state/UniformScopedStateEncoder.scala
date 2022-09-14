package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.{Decoder, Json}
import monix.bio.{IO, Task}

/**
  * Encoder contract for scoped states.
  * @tparam S
  *   the state type
  */
abstract class UniformScopedStateEncoder[S <: ScopedState] {

  /**
    * @return
    *   the resource type
    */
  def entityType: EntityType

  /**
    * @return
    *   the database decoder for the state type S
    */
  def databaseDecoder: Decoder[S]

  /**
    * Mapping function S -> UniformScopedState
    * @param state
    *   the state instance
    */
  def toUniformScopedState(state: S): Task[UniformScopedState]

  /**
    * Attempts to decode the json state into an [[UniformScopedState]].
    * @param json
    *   the state representation in json
    */
  def decode(json: Json): Task[UniformScopedState] =
    IO.fromEither(databaseDecoder.decodeJson(json)).flatMap(toUniformScopedState)
}

object UniformScopedStateEncoder {

  /**
    * Construct a UniformScopedStateEncoder from its constituents.
    * @param tpe
    *   the resource type
    * @param dbDecoder
    *   a database decoder for the state type S
    * @param f
    *   the mapping function S -> UniformScopedState
    * @tparam S
    *   the state type
    */
  def apply[S <: ScopedState](
      tpe: EntityType,
      dbDecoder: Decoder[S],
      f: S => Task[UniformScopedState]
  ): UniformScopedStateEncoder[S] =
    new UniformScopedStateEncoder[S] {
      override def entityType: EntityType                                   = tpe
      override def databaseDecoder: Decoder[S]                              = dbDecoder
      override def toUniformScopedState(state: S): Task[UniformScopedState] = f(state)
    }

  /**
    * Construct a UniformScopedStateEncoder from a serializer and state mapping function.
    * @param s
    *   the serializer
    * @param tpe
    *   the resource type
    * @param f
    *   the mapping function
    */
  def fromSerializer[Id, Value <: ScopedState](
      s: Serializer[Id, Value],
      tpe: EntityType,
      f: Value => UniformScopedState
  ): UniformScopedStateEncoder[Value] =
    apply(tpe, s.codec, f.andThen(Task.pure))

}
