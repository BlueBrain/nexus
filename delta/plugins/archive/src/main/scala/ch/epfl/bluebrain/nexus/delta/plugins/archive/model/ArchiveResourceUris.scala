package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.RootResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}

object ArchiveResourceUris {

  /**
    * Creates a [[RootResourceUris]] specific to archives.
    *
    * @param id       the archive id
    * @param project  the parent project reference
    * @param mappings the project api mappings
    * @param base     the project base
    */
  final def apply(id: Iri, project: ProjectRef, mappings: ApiMappings, base: ProjectBase): RootResourceUris = {
    val ctx = JsonLdContext(
      ContextValue.empty,
      base = Some(base.iri),
      prefixMappings = mappings.prefixMappings,
      aliases = mappings.aliases
    )

    val relative          = Uri("archives") / project.organization.value / project.project.value
    val relativeAccess    = relative / id.toString
    val relativeShortForm = relative / ctx.compact(id, useVocab = false)
    RootResourceUris(relativeAccess, relativeShortForm)
  }

}
