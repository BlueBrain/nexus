package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.ship.config.IriPatcherConfig

/**
  * Patch the iri by replacing the original prefix by the target one
  */
trait IriPatcher {
  def apply(original: Iri): Iri
}

object IriPatcher {

  val noop: IriPatcher = (original: Iri) => original

  def apply(originalPrefix: Iri, targetPrefix: Iri): IriPatcher = new IriPatcher {
    private val originalPrefixAsString = originalPrefix.toString
    override def apply(original: Iri): Iri = {
      val originalAsString = original.toString
      if (originalAsString.startsWith(originalPrefixAsString)) {
        val suffix = original.stripPrefix(originalPrefixAsString)
        targetPrefix / suffix
      } else
        original
    }
  }

  def apply(config: IriPatcherConfig): IriPatcher =
    if (config.enabled)
      IriPatcher(config.originalPrefix, config.targetPrefix)
    else noop

}
