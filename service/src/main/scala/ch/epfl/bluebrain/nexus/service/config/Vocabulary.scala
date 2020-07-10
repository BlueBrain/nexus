package ch.epfl.bluebrain.nexus.service.config

import cats.Show
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.implicits._

/**
  * Constant vocabulary values
  */
object Vocabulary {

  object skos {
    private val baseStr = "http://www.w3.org/2004/02/skos/core#"
    val prefLabel       = IriNode(url"${baseStr}prefLabel")
  }

  /**
    * Nexus archive vocabulary.
    */
  object nxva {
    implicit private[Vocabulary] val base: Iri.AbsoluteIri =
      url"https://bluebrain.github.io/nexus/vocabulary/archive/"

    /**
      * @param suffix the segment to suffix to the base
      * @return an [[IriNode]] composed by the ''base'' plus the provided ''suffix''
      */
    def withSuffix(suffix: String): IriNode = IriNode(base + suffix)

    // Archive vocabulary
    val rev     = withSuffix("rev")
    val tag     = withSuffix("tag")
    val project = withSuffix("project")
  }

  /**
    * Nexus vocabulary.
    */
  object nxv {
    val base: Iri.AbsoluteIri = url"https://bluebrain.github.io/nexus/vocabulary/"

    implicit private[Vocabulary] val toIriNode: IriNode = IriNode(base)

    /**
      * @param suffix the segment to suffix to the base
      * @return an [[IriNode]] composed by the ''base'' plus the provided ''suffix''
      */
    def withSuffix(suffix: String): IriNode = IriNode(base + suffix)

    // Metadata vocabulary
    val `@id`                 = Metadata("@id", base, "id")
    val `@base`               = Metadata("@base", base, "base")
    val `@vocab`              = Metadata("@vocab", base, "vocab")
    val reason                = Metadata("reason")
    val rev                   = Metadata("rev")
    val deprecated            = Metadata("deprecated")
    val createdAt             = Metadata("createdAt")
    val updatedAt             = Metadata("updatedAt")
    val createdBy             = Metadata("createdBy")
    val updatedBy             = Metadata("updatedBy")
    val self                  = Metadata("self")
    val project               = Metadata("project")
    val total                 = Metadata("total")
    val results               = Metadata("results")
    val maxScore              = Metadata("maxScore")
    val score                 = Metadata("score")
    val uuid                  = Metadata("uuid")
    val organizationUuid      = Metadata("organizationUuid")
    val organizationLabel     = Metadata("organizationLabel")
    val label                 = Metadata("label")
    val description           = Metadata("description")
    val apiMappings           = Metadata("apiMappings")
    val prefix                = Metadata("prefix")
    val namespace             = Metadata("namespace")
    val instant               = Metadata("instant")
    val eventSubject          = Metadata("subject")
    val grantTypes            = Metadata("grantTypes")
    val issuer                = Metadata("issuer")
    val authorizationEndpoint = Metadata("authorizationEndpoint")
    val tokenEndpoint         = Metadata("tokenEndpoint")
    val userInfoEndpoint      = Metadata("userInfoEndpoint")
    val revocationEndpoint    = Metadata("revocationEndpoint")
    val endSessionEndpoint    = Metadata("endSessionEndpoint")
    val constrainedBy         = Metadata("constrainedBy")
    val keys                  = Metadata("keys")
    val next                  = Metadata("next")
    val resourceId            = Metadata("resourceId")
    val organization          = Metadata("organization")
    val projectUuid           = Metadata("projectUuid")
    val incoming              = Metadata("incoming")
    val outgoing              = Metadata("outgoing")

    // File metadata vocabulary
    val filename  = Metadata("filename")
    val digest    = Metadata("digest")
    val algorithm = Metadata("algorithm")
    val value     = Metadata("value")
    val bytes     = Metadata("bytes")
    val mediaType = Metadata("mediaType")
    val storage   = Metadata("storage")

    // ElasticSearch sourceAsText predicate
    val original_source = Metadata("original_source")

    // Resource types
    val Project: IriNode      = withSuffix("Project")
    val Organization: IriNode = withSuffix("Organization")
    val Realm                 = withSuffix("Realm")
    val Permissions           = withSuffix("Permissions")
    val AccessControlList     = withSuffix("AccessControlList")

    // CompositeView
    val sources                  = withSuffix("sources")
    val projections              = withSuffix("projections")
    val rebuildStrategy          = withSuffix("rebuildStrategy")
    val context                  = withSuffix("context")
    val query                    = withSuffix("query")
    val token                    = withSuffix("token")
    val ElasticSearchProjection  = withSuffix("ElasticSearchProjection")
    val SparqlProjection         = withSuffix("SparqlProjection")
    val ProjectEventStream       = withSuffix("ProjectEventStream")
    val CrossProjectEventStream  = withSuffix("CrossProjectEventStream")
    val RemoteProjectEventStream = withSuffix("RemoteProjectEventStream")

    // Tagging resource payload vocabulary
    val tag  = withSuffix("tag")
    val tags = withSuffix("tags")

    // Archive vocabulary
    val resources        = withSuffix("resources")
    val originalSource   = withSuffix("originalSource")
    val file             = withSuffix("file")
    val expiresInSeconds = Metadata("expiresInSeconds")

    // Resolvers payload vocabulary
    val priority      = withSuffix("priority")
    val resourceTypes = withSuffix("resourceTypes")
    val projects      = withSuffix("projects")
    val identities    = withSuffix("identities")
    val realm         = withSuffix("realm")
    val subject       = withSuffix("subject")
    val group         = withSuffix("group")

    // View payload vocabulary
    val resourceSchemas   = withSuffix("resourceSchemas")
    val resourceTag       = withSuffix("resourceTag")
    val includeDeprecated = withSuffix("includeDeprecated")
    val includeMetadata   = withSuffix("includeMetadata")
    val sourceAsText      = withSuffix("sourceAsText")
    val mapping           = withSuffix("mapping")
    val views             = withSuffix("views")
    val viewId            = withSuffix("viewId")

    //Storage payload vocabulary
    val default         = withSuffix("default")
    val path            = withSuffix("path")
    val volume          = withSuffix("volume")
    val readPermission  = withSuffix("readPermission")
    val writePermission = withSuffix("writePermission")
    val maxFileSize     = withSuffix("maxFileSize")

    //Remote disk storage payload vocabulary
    val folder      = withSuffix("folder")
    val credentials = withSuffix("credentials")

    // S3 storage payload vocabulary
    val bucket    = withSuffix("bucket")
    val endpoint  = withSuffix("endpoint")
    val region    = withSuffix("region")
    val accessKey = withSuffix("accessKey")
    val secretKey = withSuffix("secretKey")

    // File link payload vocabulary
    val location = withSuffix("location")

    // View default ids
    val defaultElasticSearchIndex = withSuffix("defaultElasticSearchIndex")
    val defaultSparqlIndex        = withSuffix("defaultSparqlIndex")

    //Resolver default id
    val defaultResolver = withSuffix("defaultInProject")

    //Storage default id
    val defaultStorage = withSuffix("diskStorageDefault")

    //Links property
    val paths = withSuffix("paths")

    //Progress offset
    val projectionId = withSuffix("projectionId")
    val sourceId     = withSuffix("sourceId")

    // @type platform ids
    val Archive                    = withSuffix("Archive")
    val UpdateFileAttributes       = withSuffix("UpdateFileAttributes")
    val Schema                     = withSuffix("Schema")
    val File                       = withSuffix("File")
    val Resource                   = withSuffix("Resource")
    val Ontology                   = withSuffix("Ontology")
    val Resolver                   = withSuffix("Resolver")
    val InProject                  = withSuffix("InProject")
    val CrossProject               = withSuffix("CrossProject")
    val Storage                    = withSuffix("Storage")
    val RemoteDiskStorage          = withSuffix("RemoteDiskStorage")
    val DiskStorage                = withSuffix("DiskStorage")
    val Beta                       = withSuffix("Beta")
    val S3Storage                  = withSuffix("S3Storage")
    val View                       = withSuffix("View")
    val ElasticSearchView          = withSuffix("ElasticSearchView")
    val SparqlView                 = withSuffix("SparqlView")
    val CompositeView              = withSuffix("CompositeView")
    val AggregateElasticSearchView = withSuffix("AggregateElasticSearchView")
    val AggregateSparqlView        = withSuffix("AggregateSparqlView")
    val User                       = withSuffix("User")
    val Group                      = withSuffix("Group")
    val Authenticated              = withSuffix("Authenticated")
    val Anonymous                  = withSuffix("Anonymous")
    val NoOffset                   = withSuffix("NoOffset")
    val SequenceBasedOffset        = withSuffix("SequenceBasedOffset")
    val TimeBasedOffset            = withSuffix("TimeBasedOffset")
    val CompositeViewOffset        = withSuffix("CompositeViewOffset")
    val Interval                   = withSuffix("Interval")
    val ViewStatistics             = withSuffix("ViewStatistics")
    val CompositeViewStatistics    = withSuffix("CompositeViewStatistics")
  }

  /**
    * Metadata vocabulary.
    *
    * @param prefix the prefix associated to this term, used in the Json-LD context
    * @param value  the fully expanded [[AbsoluteIri]] to what the ''prefix'' resolves
    */
  final case class Metadata(prefix: String, value: AbsoluteIri, name: String)

  object Metadata {

    /**
      * Constructs a [[Metadata]] vocabulary term from the given ''base'' and the provided ''lastSegment''.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the metadata
      *                    vocabulary term
      */
    def apply(lastSegment: String)(implicit base: IriNode): Metadata =
      Metadata("_" + lastSegment, url"${base.value.show + lastSegment}", lastSegment)

    implicit def metadatataIri(m: Metadata): IriNode             = IriNode(m.value)
    implicit def metadatataAbsoluteIri(m: Metadata): AbsoluteIri = m.value
    implicit def metadataToIriF(p: Metadata): IriNode => Boolean = _ == IriNode(p.value)
    implicit val metadatataShow: Show[Metadata]                  = Show.show(_.value.show)
  }
}
