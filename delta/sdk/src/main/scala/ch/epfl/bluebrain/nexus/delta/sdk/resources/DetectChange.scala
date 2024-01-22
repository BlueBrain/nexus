package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceState
import io.circe.Json

/**
  * Detect if the new json-ld state introduces changes compared to the current state
  */
trait DetectChange {
  def apply(newValue: JsonLdAssembly, currentState: ResourceState): IO[Boolean] =
    apply(newValue, currentState.source, currentState.compacted, currentState.remoteContexts)

  def apply(
      newValue: JsonLdAssembly,
      currentSource: Json,
      currentCompacted: CompactedJsonLd,
      currentRemoteContexts: Set[RemoteContextRef]
  ): IO[Boolean]
}

object DetectChange {

  private val Disabled = new DetectChange {

    override def apply(
        newValue: JsonLdAssembly,
        currentSource: Json,
        currentCompacted: CompactedJsonLd,
        currentRemoteContexts: Set[RemoteContextRef]
    ): IO[Boolean] = IO.pure(true)
  }

  /**
    * Default implementation
    *
    * There will be a change if:
    *   - If there is a change in one of the remote JSON-LD contexts
    *   - If there is a change in the local JSON-LD context
    *   - If there is a change in the rest of the payload
    *
    * The implementation uses `IO.cede` as comparing source can induce expensive work in the case of large payloads.
    */
  private val Impl = new DetectChange {

    override def apply(
        newValue: JsonLdAssembly,
        currentSource: Json,
        currentCompacted: CompactedJsonLd,
        currentRemoteContexts: Set[RemoteContextRef]
    ): IO[Boolean] =
      IO.cede
        .as(
          newValue.remoteContexts != currentRemoteContexts ||
            newValue.compacted.ctx != currentCompacted.ctx ||
            newValue.source != currentSource
        )
        .guarantee(IO.cede)
  }

  def apply(enabled: Boolean): DetectChange = if (enabled) Impl else Disabled

}
