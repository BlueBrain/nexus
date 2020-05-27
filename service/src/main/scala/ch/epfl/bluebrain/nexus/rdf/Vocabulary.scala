package ch.epfl.bluebrain.nexus.rdf

import ch.epfl.bluebrain.nexus.rdf.syntax.iri._

// $COVERAGE-OFF$
object Vocabulary {

  /**
    * RDF vocabulary from W3C
    */
  object rdf {
    val base       = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    val first      = url"${base}first"
    val rest       = url"${base}rest"
    val nil        = url"${base}nil"
    val tpe        = url"${base}type"
    val langString = url"${base}langString"
    val value      = url"${base}value"
  }

  /**
    * OWL vocabulary from W3C
    */
  object owl {
    val base     = "http://www.w3.org/2002/07/owl#"
    val imports  = url"${base}imports"
    val sameAs   = url"${base}sameAs"
    val hasValue = url"${base}hasValue"
    val oneOf    = url"${base}oneOf"
    val Ontology = url"${base}Ontology"
    val Class    = url"${base}Class"
  }

  /**
    * XSD vocabulary from W3C
    */
  object xsd {
    val base               = "http://www.w3.org/2001/XMLSchema#"
    val dateTime           = url"${base}dateTime"
    val date               = url"${base}date"
    val time               = url"${base}time"
    val string             = url"${base}string"
    val boolean            = url"${base}boolean"
    val byte               = url"${base}byte"
    val short              = url"${base}short"
    val int                = url"${base}int"
    val integer            = url"${base}integer"
    val long               = url"${base}long"
    val decimal            = url"${base}decimal"
    val double             = url"${base}double"
    val float              = url"${base}float"
    val negativeInteger    = url"${base}negativeInteger"
    val nonNegativeInteger = url"${base}nonNegativeInteger"
    val nonPositiveInteger = url"${base}nonPositiveInteger"
    val positiveInteger    = url"${base}positiveInteger"
    val unsignedByte       = url"${base}unsignedByte"
    val unsignedShort      = url"${base}unsignedShort"
    val unsignedInt        = url"${base}unsignedInt"
    val unsignedLong       = url"${base}unsignedLong"
  }

  /**
    * XMLSchema vocabulary
    */
  object xml {
    val base = "http://www.w3.org/2001/XMLSchema#"
    val int  = url"${base}int"
  }

  /**
    * Schema.org vocabulary
    */
  object schema {
    val base              = "http://schema.org/"
    val age               = url"${base}age"
    val description       = url"${base}description"
    val name              = url"${base}name"
    val unitText          = url"${base}unitText"
    val value             = url"${base}value"
    val Person            = url"${base}Person"
    val QuantitativeValue = url"${base}QuantitativeValue"
  }

  /**
    * Nexus vocabulary
    */
  object nxv {
    val base = url"https://bluebrain.github.io/nexus/vocabulary/"
  }
}
// $COVERAGE-ON$
