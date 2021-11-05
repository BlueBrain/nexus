package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe.withoutConfig
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeDef.noConfig
import monix.bio.Task

/**
  * Filters out deprecated resources
  */
object FilterDeprecated {

  val name = "filterDeprecated"

  val pipe: Pipe =
    withoutConfig(
      name,
      (data: IndexingData) => Task.pure(Option.when(!data.deprecated)(data))
    )

  def apply(): PipeDef = noConfig(name)

}
