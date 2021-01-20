package ch.epfl.bluebrain.nexus.delta.rdf
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
    * SHACL vocabulary.
    */
  object sh {
    val base              = iri"http://www.w3.org/ns/shacl#"
    def +(suffix: String) = iri"$base$suffix"

    val ValidationReport = sh + "ValidationReport"
    val conforms         = sh + "conforms"
  }

  /**
    * Nexus SHACL vocabulary.
    */
  object nxsh {
    val base              = iri"https://bluebrain.github.io/nexus/vocabulary/shacl/"
    def +(suffix: String) = iri"$base$suffix"

    val targetedNodes = nxsh + "targetedNodes"
  }

  /**
    * Nexus vocabulary
    */
  object nxv {
    implicit val base     = iri"https://bluebrain.github.io/nexus/vocabulary/"
    def +(suffix: String) = iri"$base$suffix"

    // Metadata vocabulary
    val authorizationEndpoint = Metadata("authorizationEndpoint")
    val createdAt             = Metadata("createdAt")
    val createdBy             = Metadata("createdBy")
    val deprecated            = Metadata("deprecated")
    val endSessionEndpoint    = Metadata("endSessionEndpoint")
    val eventSubject          = Metadata("subject")(iri"${base}metadata/")
    val grantTypes            = Metadata("grantTypes")
    val instant               = Metadata("instant")
    val issuer                = Metadata("issuer")
    val label                 = Metadata("label")
    val maxScore              = Metadata("maxScore")
    val next                  = Metadata("next")
    val organizationLabel     = Metadata("organizationLabel")
    val organizationUuid      = Metadata("organizationUuid")
    val project               = Metadata("project")
    val resolverId            = Metadata("resolverId")
    val resourceId            = Metadata("resourceId")
    val schemaId              = Metadata("schemaId")
    val results               = Metadata("results")
    val rev                   = Metadata("rev")
    val revocationEndpoint    = Metadata("revocationEndpoint")
    val score                 = Metadata("score")
    val self                  = Metadata("self")
    val source                = Metadata("source")
    val tokenEndpoint         = Metadata("tokenEndpoint")
    val total                 = Metadata("total")
    val types                 = Metadata("types")
    val updatedAt             = Metadata("updatedAt")
    val updatedBy             = Metadata("updatedBy")
    val userInfoEndpoint      = Metadata("userInfoEndpoint")
    val uuid                  = Metadata("uuid")
    val path                  = Metadata("path")

    // Resource types
    val AccessControlList = nxv + "AccessControlList"
    val Organization      = nxv + "Organization"
    val Permissions       = nxv + "Permissions"
    val Project           = nxv + "Project"
    val Realm             = nxv + "Realm"
    val Resolver          = nxv + "Resolver"
    val InProject         = nxv + "InProject"
    val CrossProject      = nxv + "CrossProject"
    val Schema            = nxv + "Schema"
  }

  /**
    * Nexus schemas
    */
  object schemas {
    val base              = iri"https://bluebrain.github.io/nexus/schemas/"
    def +(suffix: String) = iri"$base$suffix"

    val acls          = schemas + "acls.json"
    val shacl         = iri"https://bluebrain.github.io/nexus/schemas/shacl-20170720.ttl"
    val organizations = schemas + "organizations.json"
    val permissions   = schemas + "permissions.json"
    val projects      = schemas + "projects.json"
    val realms        = schemas + "realms.json"
    val resources     = schemas + "resources.json"
    val resolvers     = schemas + "resolvers.json"
  }

  /**
    * Nexus contexts
    */
  object contexts {
    val base              = iri"https://bluebrain.github.io/nexus/contexts/"
    def +(suffix: String) = iri"$base$suffix"

    val acls          = contexts + "acls.json"
    val error         = contexts + "error.json"
    val identities    = contexts + "identities.json"
    val metadata      = contexts + "metadata.json"
    val organizations = contexts + "organizations.json"
    val permissions   = contexts + "permissions.json"
    val pluginsInfo   = contexts + "plugins-info.json"
    val projects      = contexts + "projects.json"
    val realms        = contexts + "realms.json"
    val resolvers     = contexts + "resolvers.json"
    val search        = contexts + "search.json"
    val shacl         = iri"https://bluebrain.github.io/nexus/contexts/shacl-20170720.json"
    val tags          = contexts + "tags.json"
  }

  /**
    * Metadata vocabulary.
    *
    * @param prefix the prefix associated to this term, used in the Json-LD context
    * @param iri    the fully expanded [[Iri]] to what the ''prefix'' resolves
    * @param name   the name of the metadata
    */
  final case class Metadata(prefix: String, iri: Iri, name: String)

  object Metadata {

    /**
      * Constructs a [[Metadata]] vocabulary term from the given ''base'' and the provided ''lastSegment''.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the metadata
      *                    vocabulary term
      */
    def apply(lastSegment: String)(implicit base: Iri): Metadata =
      Metadata("_" + lastSegment, iri"$base$lastSegment", lastSegment)
  }
}
// $COVERAGE-ON$
