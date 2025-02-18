package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import com.apicatalog.jsonld.{JsonLdEmbed, JsonLdVersion}

/**
  * Json-LD Options defined by https://www.w3.org/TR/json-ld11-api/#dom-jsonldoptions
  */
final case class JsonLdOptions(
    base: Option[Iri] = None,
    compactArrays: Boolean = true,
    compactToRelative: Boolean = true,
    extractAllScripts: Boolean = false,
    ordered: Boolean = false,
    processingMode: JsonLdVersion = JsonLdVersion.V1_1,
    produceGeneralizedRdf: Boolean = true,
    rdfDirection: Option[String] = None,
    useNativeTypes: Boolean = true,
    useRdfType: Boolean = false,
    embed: JsonLdEmbed = JsonLdEmbed.ONCE,
    explicit: Boolean = false,
    omitDefault: Boolean = false,
    omitGraph: Boolean = true
)

object JsonLdOptions {
  implicit val defaults: JsonLdOptions = JsonLdOptions()
  val AlwaysEmbed: JsonLdOptions       = defaults.copy(embed = JsonLdEmbed.ALWAYS)

}
