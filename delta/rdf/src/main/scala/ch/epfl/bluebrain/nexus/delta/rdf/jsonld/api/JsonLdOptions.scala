package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Json-LD Options defined by https://www.w3.org/TR/json-ld11-api/#dom-jsonldoptions
  *
  * Note that some there are some fields from JSON-LD 1.0 and some from JSON-LD 1.1 since the
  * JSON-LD implementaiton does not fully support 1.1 yet
  */
final case class JsonLdOptions(
    base: Option[Iri] = None,
    compactArrays: Boolean = true,
    compactToRelative: Boolean = true,
    extractAllScripts: Boolean = false,
    ordered: Boolean = false,
    processingMode: String = "json-ld-1.1",
    produceGeneralizedRdf: Boolean = true,
    rdfDirection: Option[String] = None,
    useNativeTypes: Boolean = true,
    useRdfType: Boolean = false,
    embed: String = "@last",
    explicit: Boolean = false,
    omitDefault: Boolean = false,
    omitGraph: Boolean = true,
    requiredAll: Boolean = false,
    pruneBlankNodeIdentifiers: Boolean = true
)

object JsonLdOptions {
  implicit val defaults: JsonLdOptions = JsonLdOptions()
}
