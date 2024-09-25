package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, Transactors}
import doobie.syntax.all._

object ScopedStateStoreFixture {

  private val queryConfig = QueryConfig(5, RefreshStrategy.Stop)

  def store[Id, S <: ScopedState](
      tpe: EntityType,
      serializer: Serializer[Id, S]
  )(saveLatest: List[S], saveTagged: List[(UserTag, S)]): Resource[IO, (Transactors, ScopedStateStore[Id, S])] =
    Doobie.resource().evalMap { xas =>
      val stateStore = ScopedStateStore(tpe, serializer, queryConfig, xas)
      (
        saveLatest.traverse(stateStore.unsafeSave) >>
          saveTagged.traverse { case (tag, s) => stateStore.unsafeSave(s, tag) }
      ).transact(xas.write).as {
        (xas, stateStore)
      }
    }
}
