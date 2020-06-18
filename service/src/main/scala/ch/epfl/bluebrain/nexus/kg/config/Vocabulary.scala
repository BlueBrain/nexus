package ch.epfl.bluebrain.nexus.kg.config

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

  /**
    * Nexus archive vocabulary.
    */
  object nxva {
    implicit private[Vocabulary] val uri: Iri.AbsoluteIri =
      url"https://bluebrain.github.io/nexus/vocabulary/archive/"

    // Archive vocabulary
    val rev     = PrefixMapping.prefix("rev")
    val tag     = PrefixMapping.prefix("tag")
    val project = PrefixMapping.prefix("project")
  }

  /**
    * Nexus vocabulary.
    */
  object nxv {
    val base: Iri.AbsoluteIri                             = url"https://bluebrain.github.io/nexus/vocabulary/"
    implicit private[Vocabulary] val uri: Iri.AbsoluteIri = base

    /**
      * @param suffix the segment to suffix to the base
      * @return an [[IriNode]] composed by the ''base'' plus the provided ''suffix''
      */
    def withSuffix(suffix: String): IriNode = IriNode(base + suffix)

    // Metadata vocabulary
    val rev              = PrefixMapping.metadata("rev")
    val deprecated       = PrefixMapping.metadata("deprecated")
    val createdAt        = PrefixMapping.metadata("createdAt")
    val updatedAt        = PrefixMapping.metadata("updatedAt")
    val createdBy        = PrefixMapping.metadata("createdBy")
    val updatedBy        = PrefixMapping.metadata("updatedBy")
    val constrainedBy    = PrefixMapping.metadata("constrainedBy")
    val self             = PrefixMapping.metadata("self")
    val project          = PrefixMapping.metadata("project")
    val total            = PrefixMapping.metadata("total")
    val next             = PrefixMapping.metadata("next")
    val results          = PrefixMapping.metadata("results")
    val maxScore         = PrefixMapping.metadata("maxScore")
    val score            = PrefixMapping.metadata("score")
    val uuid             = PrefixMapping.metadata("uuid")
    val instant          = PrefixMapping.metadata("instant")
    val eventSubject     = PrefixMapping.metadata("subject")
    val resourceId       = PrefixMapping.metadata("resourceId")
    val organization     = PrefixMapping.metadata("organization")
    val projectUuid      = PrefixMapping.metadata("projectUuid")
    val organizationUuid = PrefixMapping.metadata("organizationUuid")
    val incoming         = PrefixMapping.metadata("incoming")
    val outgoing         = PrefixMapping.metadata("outgoing")

    // File metadata vocabulary
    val filename  = PrefixMapping.metadata("filename")
    val digest    = PrefixMapping.metadata("digest")
    val algorithm = PrefixMapping.metadata("algorithm")
    val value     = PrefixMapping.metadata("value")
    val bytes     = PrefixMapping.metadata("bytes")
    val mediaType = PrefixMapping.metadata("mediaType")
    val storage   = PrefixMapping.metadata("storage")

    // ElasticSearch sourceAsText predicate
    val original_source = PrefixMapping.metadata("original_source")

    // CompositeView
    val sources                  = PrefixMapping.prefix("sources")
    val projections              = PrefixMapping.prefix("projections")
    val rebuildStrategy          = PrefixMapping.prefix("rebuildStrategy")
    val context                  = PrefixMapping.prefix("context")
    val query                    = PrefixMapping.prefix("query")
    val token                    = PrefixMapping.prefix("token")
    val ElasticSearchProjection  = PrefixMapping.prefix("ElasticSearchProjection")
    val SparqlProjection         = PrefixMapping.prefix("SparqlProjection")
    val ProjectEventStream       = PrefixMapping.prefix("ProjectEventStream")
    val CrossProjectEventStream  = PrefixMapping.prefix("CrossProjectEventStream")
    val RemoteProjectEventStream = PrefixMapping.prefix("RemoteProjectEventStream")

    // Tagging resource payload vocabulary
    val tag  = PrefixMapping.prefix("tag")
    val tags = PrefixMapping.prefix("tags")

    // Archive vocabulary
    val resources        = PrefixMapping.prefix("resources")
    val originalSource   = PrefixMapping.prefix("originalSource")
    val file             = PrefixMapping.prefix("file")
    val expiresInSeconds = PrefixMapping.metadata("expiresInSeconds")

    // Resolvers payload vocabulary
    val priority      = PrefixMapping.prefix("priority")
    val resourceTypes = PrefixMapping.prefix("resourceTypes")
    val projects      = PrefixMapping.prefix("projects")
    val identities    = PrefixMapping.prefix("identities")
    val realm         = PrefixMapping.prefix("realm")
    val subject       = PrefixMapping.prefix("subject")
    val group         = PrefixMapping.prefix("group")

    // View payload vocabulary
    val resourceSchemas   = PrefixMapping.prefix("resourceSchemas")
    val resourceTag       = PrefixMapping.prefix("resourceTag")
    val includeDeprecated = PrefixMapping.prefix("includeDeprecated")
    val includeMetadata   = PrefixMapping.prefix("includeMetadata")
    val sourceAsText      = PrefixMapping.prefix("sourceAsText")
    val mapping           = PrefixMapping.prefix("mapping")
    val views             = PrefixMapping.prefix("views")
    val viewId            = PrefixMapping.prefix("viewId")

    //Storage payload vocabulary
    val default         = PrefixMapping.prefix("default")
    val path            = PrefixMapping.prefix("path")
    val volume          = PrefixMapping.prefix("volume")
    val readPermission  = PrefixMapping.prefix("readPermission")
    val writePermission = PrefixMapping.prefix("writePermission")
    val maxFileSize     = PrefixMapping.prefix("maxFileSize")

    //Remote disk storage payload vocabulary
    val folder      = PrefixMapping.prefix("folder")
    val credentials = PrefixMapping.prefix("credentials")

    // S3 storage payload vocabulary
    val bucket    = PrefixMapping.prefix("bucket")
    val endpoint  = PrefixMapping.prefix("endpoint")
    val region    = PrefixMapping.prefix("region")
    val accessKey = PrefixMapping.prefix("accessKey")
    val secretKey = PrefixMapping.prefix("secretKey")

    // File link payload vocabulary
    val location = PrefixMapping.prefix("location")

    // View default ids
    val defaultElasticSearchIndex = PrefixMapping.prefix("defaultElasticSearchIndex")
    val defaultSparqlIndex        = PrefixMapping.prefix("defaultSparqlIndex")

    //Resolver default id
    val defaultResolver = PrefixMapping.prefix("defaultInProject")

    //Storage default id
    val defaultStorage = PrefixMapping.prefix("diskStorageDefault")

    //Links property
    val paths = PrefixMapping.prefix("paths")

    //Progress offset
    val projectionId = PrefixMapping.prefix("projectionId")
    val sourceId     = PrefixMapping.prefix("sourceId")

    // @type platform ids
    val Archive                    = PrefixMapping.prefix("Archive")
    val UpdateFileAttributes       = PrefixMapping.prefix("UpdateFileAttributes")
    val Schema                     = PrefixMapping.prefix("Schema")
    val File                       = PrefixMapping.prefix("File")
    val Resource                   = PrefixMapping.prefix("Resource")
    val Ontology                   = PrefixMapping.prefix("Ontology")
    val Resolver                   = PrefixMapping.prefix("Resolver")
    val InProject                  = PrefixMapping.prefix("InProject")
    val CrossProject               = PrefixMapping.prefix("CrossProject")
    val Storage                    = PrefixMapping.prefix("Storage")
    val RemoteDiskStorage          = PrefixMapping.prefix("RemoteDiskStorage")
    val DiskStorage                = PrefixMapping.prefix("DiskStorage")
    val Beta                       = PrefixMapping.prefix("Beta")
    val S3Storage                  = PrefixMapping.prefix("S3Storage")
    val View                       = PrefixMapping.prefix("View")
    val ElasticSearchView          = PrefixMapping.prefix("ElasticSearchView")
    val SparqlView                 = PrefixMapping.prefix("SparqlView")
    val CompositeView              = PrefixMapping.prefix("CompositeView")
    val AggregateElasticSearchView = PrefixMapping.prefix("AggregateElasticSearchView")
    val AggregateSparqlView        = PrefixMapping.prefix("AggregateSparqlView")
    val User                       = PrefixMapping.prefix("User")
    val Group                      = PrefixMapping.prefix("Group")
    val Authenticated              = PrefixMapping.prefix("Authenticated")
    val Anonymous                  = PrefixMapping.prefix("Anonymous")
    val AccessControlList          = PrefixMapping.prefix("AccessControlList")
    val NoOffset                   = PrefixMapping.prefix("NoOffset")
    val SequenceBasedOffset        = PrefixMapping.prefix("SequenceBasedOffset")
    val TimeBasedOffset            = PrefixMapping.prefix("TimeBasedOffset")
    val CompositeViewOffset        = PrefixMapping.prefix("CompositeViewOffset")
    val Interval                   = PrefixMapping.prefix("Interval")
    val ViewStatistics             = PrefixMapping.prefix("ViewStatistics")
    val CompositeViewStatistics    = PrefixMapping.prefix("CompositeViewStatistics")
  }

  /**
    * Prefix mapping.
    *
    * @param prefix the prefix associated to this term, used in the Json-LD context
    * @param value  the fully expanded [[AbsoluteIri]] to what the ''prefix'' resolves
    */
  final case class PrefixMapping(prefix: String, value: AbsoluteIri)

  object PrefixMapping {

    /**
      * Constructs a [[PrefixMapping]] vocabulary term from the given ''base'' and the provided ''lastSegment'' with an '_'.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the metadata vocabulary term
      */
    def metadata(lastSegment: String)(implicit base: AbsoluteIri): PrefixMapping =
      PrefixMapping("_" + lastSegment, url"${base.show + lastSegment}")

    /**
      * Constructs a [[PrefixMapping]] vocabulary term from the given ''base'' and the provided ''lastSegment''.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the vocabulary term
      */
    def prefix(lastSegment: String)(implicit base: AbsoluteIri): PrefixMapping =
      new PrefixMapping(lastSegment, IriNode(base + lastSegment).value)

    implicit def prefixMappingIri(m: PrefixMapping): IriNode               = IriNode(m.value)
    implicit def prefixMappingAbsoluteIri(m: PrefixMapping): AbsoluteIri   = m.value
    implicit def prefixMappingToIriF(p: PrefixMapping): IriNode => Boolean = _ == IriNode(p.value)
    implicit val prefixMappingShow: Show[PrefixMapping]                    = Show.show(_.value.show)
  }
}
