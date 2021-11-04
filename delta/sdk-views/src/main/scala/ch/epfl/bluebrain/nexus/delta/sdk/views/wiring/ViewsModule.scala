package ch.epfl.bluebrain.nexus.delta.sdk.views.wiring

import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import izumi.distage.model.definition.ModuleDef

/**
  * Views wiring
  */
object ViewsModule extends ModuleDef {

  many[Pipe].addSetValue(
    PipeConfig.builtInPipes
  )

  make[PipeConfig].from { (pipes: Set[Pipe]) =>
    PipeConfig(pipes).fold(e => throw new IllegalArgumentException(e), identity)
  }

}
