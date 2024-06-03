package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.ship.config.InputConfig.ProjectMapping
import ch.epfl.bluebrain.nexus.ship.config.IriPatcherConfig

/**
  * Patch the iri by replacing the original prefix by the target one
  */
trait IriPatcher {

  def enabled: Boolean

  def apply(original: Iri): Iri
}

object IriPatcher {

  val noop: IriPatcher = new IriPatcher {
    override def enabled: Boolean = false

    override def apply(original: Iri): Iri = original
  }

  def apply(originalPrefix: Iri, targetPrefix: Iri, projectMapping: ProjectMapping): IriPatcher = new IriPatcher {
    private val originalPrefixAsString = originalPrefix.toString
    override def apply(original: Iri): Iri = {
      val originalAsString = original.toString
      if (originalAsString.startsWith(originalPrefixAsString)) {
        val suffix                  = original.stripPrefix(originalPrefixAsString)
        val suffixWithMappedProject = projectMapping.foldLeft(suffix) {
          case (accSuffix, (originalProject, targetProject)) =>
            accSuffix.replaceAll(originalProject.toString, targetProject.toString)
        }

        targetPrefix / suffixWithMappedProject
      } else
        original
    }

    override def enabled: Boolean = true
  }

  def apply(config: IriPatcherConfig, projectMapping: ProjectMapping): IriPatcher =
    if (config.enabled)
      IriPatcher(config.originalPrefix, config.targetPrefix, projectMapping)
    else noop

}
