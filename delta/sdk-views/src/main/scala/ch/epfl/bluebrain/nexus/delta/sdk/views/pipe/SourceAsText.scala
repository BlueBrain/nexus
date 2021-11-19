package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe.withoutConfig
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeDef.noConfig
import io.circe.Json
import monix.bio.Task

/**
  * Allows to index source as a field text
  */
object SourceAsText {
  private val empty = Json.obj()
  val name          = "sourceAsText"

  val pipe: Pipe =
    withoutConfig(
      name,
      (data: IndexingData) =>
        Task.some(
          data.copy(
            metadataGraph = data.metadataGraph.add(nxv.originalSource.iri, data.source.noSpaces),
            source = empty
          )
        )
    )

  def apply(): PipeDef = noConfig(name)

}
