package ch.epfl.bluebrain.nexus.delta.rdf
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._

// $COVERAGE-OFF$
object Vocabulary {

  /**
    * RDF vocabulary from W3C
    */
  object rdf {
    val base              = iri"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    def +(suffix: String) = iri"$base$suffix"

    val first      = rdf + "first"
    val rest       = rdf + "rest"
    val nil        = rdf + "nil"
    val tpe        = rdf + "type"
    val langString = rdf + "langString"
    val value      = rdf + "value"
  }

  object rdfs {
    val base              = iri"http://www.w3.org/2000/01/rdf-schema#"
    def +(suffix: String) = iri"$base$suffix"

    val label = rdfs + "label"
  }

  /**
    * OWL vocabulary from W3C
    */
  object owl {
    val base              = iri"http://www.w3.org/2002/07/owl#"
    def +(suffix: String) = iri"$base$suffix"

    val imports  = owl + "imports"
    val sameAs   = owl + "sameAs"
    val hasValue = owl + "hasValue"
    val oneOf    = owl + "oneOf"
    val Ontology = owl + "Ontology"
    val Class    = owl + "Class"

  }

  /**
    * XSD vocabulary from W3C
    */
  object xsd {
    val base              = iri"http://www.w3.org/2001/XMLSchema#"
    def +(suffix: String) = iri"$base$suffix"

    val dateTime = xsd + "dateTime"
    val date     = xsd + "date"
    val time     = xsd + "time"
    val string   = xsd + "string"
    val boolean  = xsd + "boolean"
    val byte     = xsd + "byte"
    val short    = xsd + "short"
    val int      = xsd + "int"
    val integer  = xsd + "integer"
    val long     = xsd + "long"
    val decimal  = xsd + "decimal"
    val double   = xsd + "double"
    val float    = xsd + "float"
  }

  /**
    * XMLSchema vocabulary
    */
  object xml {
    val base              = iri"http://www.w3.org/2001/XMLSchema#"
    def +(suffix: String) = iri"$base$suffix"

    val int = xml + "int"
  }

  /**
    * Schema.org vocabulary
    */
  object schema {
    val base              = iri"http://schema.org/"
    def +(suffix: String) = iri"$base$suffix"

    val age               = schema + "age"
    val description       = schema + "description"
    val name              = schema + "name"
    val unitText          = schema + "unitText"
    val value             = schema + "value"
    val Person            = schema + "Person"
    val QuantitativeValue = schema + "QuantitativeValue"
  }

  /**
    * Nexus vocabulary
    */
  object nxv {
    val base              = iri"https://bluebrain.github.io/nexus/vocabulary/"
    def +(suffix: String) = iri"$base$suffix"

    val AccessControlList = nxv + "AccessControlList"
    val Permissions       = nxv + "Permissions"
    val Realm             = nxv + "Realm"
    val Organization = nxv + "Organization"
  }

  /**
    * Nexus schemas
    */
  object schemas {
    val base              = iri"https://bluebrain.github.io/nexus/schemas/"
    def +(suffix: String) = iri"$base$suffix"

    val acls        = schemas + "acls.json"
    val permissions = schemas + "permissions.json"
    val realms      = schemas + "realms.json"
    val organizations        = schemas + "organizations.json"
  }
}
// $COVERAGE-ON$
