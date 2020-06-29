package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant}

import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.StorageReference._
import ch.epfl.bluebrain.nexus.kg.resources.file.File.FileAttributes
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Graph._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri, Node}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.{nxv, Metadata}
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import io.circe.{Decoder, DecodingFailure, Json}

/**
  * A resource representation.
  *
  * @param id         the unique identifier of the resource in a given project
  * @param rev        the revision of the resource
  * @param types      the collection of known types of this resource
  * @param deprecated whether the resource is deprecated of not
  * @param tags       the collection of tag names to revisions of the resource
  * @param file       the optional file metadata with the storage reference where the file was saved
  * @param created    the instant when this resource was created
  * @param updated    the last instant when this resource was updated
  * @param createdBy  the identity that created this resource
  * @param updatedBy  the last identity that updated this resource
  * @param schema     the schema that this resource conforms to
  * @param value      the resource value
  * @tparam A the resource value type
  */
final case class ResourceF[A](
    id: Id[ProjectRef],
    rev: Long,
    types: Set[AbsoluteIri],
    deprecated: Boolean,
    tags: Map[String, Long],
    file: Option[(StorageReference, FileAttributes)],
    created: Instant,
    updated: Instant,
    createdBy: Identity,
    updatedBy: Identity,
    schema: Ref,
    value: A
) {

  /**
    * Applies the argument function to the resource value yielding a new resource.
    *
    * @param f the value mapping
    * @tparam B the output type of the mapping
    * @return a new resource with a mapped value
    */
  def map[B](f: A => B): ResourceF[B] =
    copy(value = f(value))

  /**
    * An IriNode for the @id of the resource.
    */
  lazy val node: IriNode = IriNode(id.value)

  /**
    * Computes the metadata triples for this resource.
    */
  def metadata(
      options: MetadataOptions = MetadataOptions()
  )(implicit config: ServiceConfig, project: Project): Set[Triple] = {

    def showLocation(storageRef: StorageReference): Boolean =
      storageRef match {
        case _: DiskStorageReference       => config.kg.storage.disk.showLocation
        case _: RemoteDiskStorageReference => config.kg.storage.remoteDisk.showLocation
        case _: S3StorageReference         => config.kg.storage.amazon.showLocation
      }

    def outAndInWhenNeeded(self: AbsoluteIri): Set[Triple] = {
      lazy val incoming = self + "incoming"
      lazy val outgoing = self + "outgoing"

      if (schema.iri == archiveSchemaUri)
        Set.empty
      else if (options.linksAsIri)
        Set[Triple]((node, nxv.incoming, incoming), (node, nxv.outgoing, outgoing))
      else
        Set[Triple]((node, nxv.incoming, incoming.toString), (node, nxv.outgoing, outgoing.toString))
    }

    def triplesFor(storageAndAttributes: (StorageReference, FileAttributes)): Set[Triple] = {
      val blankDigest      = Node.blank
      val (storageRef, at) = storageAndAttributes
      val triples          = Set[Triple](
        (blankDigest, nxv.algorithm, at.digest.algorithm),
        (blankDigest, nxv.value, at.digest.value),
        (storageRef.id, nxv.rev, storageRef.rev),
        (node, nxv.storage, storageRef.id),
        (node, rdf.tpe, nxv.File),
        (node, nxv.bytes, at.bytes),
        (node, nxv.digest, blankDigest),
        (node, nxv.mediaType, at.mediaType.value),
        (node, nxv.filename, at.filename)
      )
      if (showLocation(storageRef)) triples + ((node, nxv.location, at.location.toString()): Triple)
      else triples
    }

    val fileTriples = file.map(triplesFor).getOrElse(Set.empty)
    val projectUri  =
      config.http.prefixIri + "projects" / project.organizationLabel / project.label
    val self        = AccessId(id.value, schema.iri, expanded = options.expandedLinks)
    fileTriples ++ Set[Triple](
      (node, nxv.rev, rev),
      (node, nxv.deprecated, deprecated),
      (node, nxv.createdAt, created),
      (node, nxv.updatedAt, updated),
      (node, nxv.createdBy, if (options.linksAsIri) createdBy.id else createdBy.id.asString),
      (node, nxv.updatedBy, if (options.linksAsIri) updatedBy.id else updatedBy.id.asString),
      (node, nxv.constrainedBy, schema.iri),
      (node, nxv.project, projectUri),
      (node, nxv.self, if (options.linksAsIri) self else self.asString)
    ) ++ typeTriples ++ outAndInWhenNeeded(self)
  }

  /**
    * The triples for the type of this resource.
    */
  private lazy val typeTriples: Set[Triple] = types.map(tpe => (node, rdf.tpe, tpe): Triple)

}

object ResourceF {

  implicit private val identityDecoderByAbolsuteIri: Decoder[Identity] =
    Decoder.decodeString.emap {
      Iri.absolute(_).flatMap(iri => Identity(iri).toRight(s"'$iri' could not be transformed into an Identity"))
    }

  def resourceVGraphDecoder(projectRef: ProjectRef): Decoder[ResourceV] =
    resourceGraphDecoder(projectRef).map {
      case ResourceF(id, rev, types, deprecated, tags, file, created, updated, cBy, uBy, schema, graph) =>
        // format: off
      ResourceF(id, rev, types, deprecated, tags, file, created, updated, cBy, uBy, schema, Value(Json.obj(), Json.obj(), graph))
    // format: on
    }

  def resourceGraphDecoder(projectRef: ProjectRef): Decoder[ResourceGraph] =
    // format: off
    Decoder.instance { hc =>
      for {
        id         <- hc.get[AbsoluteIri]("@id").map(id => Id(projectRef, id))
        types      <- hc.get[Option[Set[AbsoluteIri]]]("@type") orElse hc.get[Option[AbsoluteIri]]("@type").map(_.map(Set(_)))
        rev        <- hc.get[Long](nxv.rev.value.asString)
        deprecated <- hc.get[Boolean](nxv.deprecated.value.asString)
        created    <- hc.get[Instant](nxv.createdAt.value.asString)
        updated    <- hc.get[Instant](nxv.updatedAt.value.asString)
        cBy        <- hc.get[Identity](nxv.createdBy.value.asString)
        uBy        <- hc.get[Identity](nxv.updatedBy.value.asString)
        schema     <- hc.downField(nxv.constrainedBy.value.asString).get[AbsoluteIri]("@id").map(Ref(_))
        graph      <- hc.value.toGraph(id.value).left.map(er => DecodingFailure(er, hc.history))
      } yield ResourceF(id, rev, types.getOrElse(Set.empty[AbsoluteIri]), deprecated, Map.empty, None, created, updated, cBy, uBy, schema, graph)
    }
  // format: on

  val metaPredicates: Set[Metadata] = Set[Metadata](
    nxv.rev,
    nxv.deprecated,
    nxv.createdAt,
    nxv.updatedAt,
    nxv.createdBy,
    nxv.updatedBy,
    nxv.constrainedBy,
    nxv.self,
    nxv.project,
    nxv.organization,
    nxv.organizationUuid,
    nxv.projectUuid,
    nxv.incoming,
    nxv.outgoing
  )

  val metaKeys: Seq[String] = metaPredicates.map(_.prefix).toSeq

  private val metaIris: Set[IriNode] = metaPredicates.map(p => IriNode(p.value))

  /**
    * Removes the metadata triples from the rooted graph
    *
    * @return a new [[Graph]] without the metadata triples
    */
  def removeMetadata(graph: Graph): Graph =
    graph.filter {
      case (s, p, _) => !(metaIris.contains(p) && s == graph.root)
    }

  /**
    * A default resource value type.
    *
    * @param source the source value of a resource
    * @param ctx    an expanded (flattened) context value
    * @param graph  a graph representation of a resource
    */
  final case class Value(source: Json, ctx: Json, graph: Graph)

  /**
    * Construct a [[ResourceF]] with default parameters
    *
    * @param id         the unique identifier of the resource
    * @param value      the [[Json]] resource value
    * @param rev        the revision of the resource
    * @param types      the collection of known types of this resource
    * @param deprecated whether the resource is deprecated of not
    * @param schema     the schema that this resource conforms to
    * @param created    the identity that created this resource
    * @param updated    the last identity that updated this resource
    */
  def simpleF(
      id: Id[ProjectRef],
      value: Json,
      rev: Long = 1L,
      types: Set[AbsoluteIri] = Set.empty,
      deprecated: Boolean = false,
      schema: Ref = unconstrainedSchemaUri.ref,
      created: Identity = Anonymous,
      updated: Identity = Anonymous
  )(implicit clock: Clock): ResourceF[Json] =
    ResourceF(
      id,
      rev,
      types,
      deprecated,
      Map.empty,
      None,
      clock.instant(),
      clock.instant(),
      created,
      updated,
      schema,
      value
    )

  /**
    * Construct a [[ResourceF]] with default parameters
    *
    * @param id         the unique identifier of the resource
    * @param value      the [[Value]] resource value
    * @param rev        the revision of the resource
    * @param types      the collection of known types of this resource
    * @param deprecated whether the resource is deprecated of not
    * @param schema     the schema that this resource conforms to
    * @param created    the identity that created this resource
    * @param updated    the last identity that updated this resource
    */
  def simpleV(
      id: Id[ProjectRef],
      value: Value,
      rev: Long = 1L,
      types: Set[AbsoluteIri] = Set.empty,
      deprecated: Boolean = false,
      schema: Ref = unconstrainedSchemaUri.ref,
      created: Identity = Anonymous,
      updated: Identity = Anonymous
  )(implicit clock: Clock = Clock.systemUTC): ResourceF[Value] =
    ResourceF(
      id,
      rev,
      types,
      deprecated,
      Map.empty,
      None,
      clock.instant(),
      clock.instant(),
      created,
      updated,
      schema,
      value
    )
}

/**
  * Metadata output options
  *
  * @param linksAsIri    flag to decide whether or not the links are represented as Iri or string
  * @param expandedLinks flag to decide whether or not the links are expanded using the project prefix mappings and base or not
  */
final case class MetadataOptions(linksAsIri: Boolean = false, expandedLinks: Boolean = false)
