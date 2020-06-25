package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.Resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.blank
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd.IdRetrievalError
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import io.circe.Json
import io.circe.syntax._

/**
  * Resource operations.
  */
class Resources[F[_]](implicit
    F: Effect[F],
    val repo: Repo[F],
    materializer: Materializer[F],
    config: ServiceConfig
) {

  private val emptyJson = Json.obj()

  /**
    * Creates a new resource attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param schema     a schema reference that constrains the resource
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(schema: Ref, source: Json)(implicit subject: Subject, project: Project): RejOrResource[F] = {
    val sourceWithCtx = addContextIfEmpty(source)
    materializer(sourceWithCtx).flatMap {
      case (id, Value(_, _, graph)) => create(Id(project.ref, id), schema, sourceWithCtx, graph.removeMetadata)
    }
  }

  /**
    * Creates a new resource.
    *
    * @param id     the id of the resource
    * @param schema a schema reference that constrains the resource
    * @param source the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(id: ResId, schema: Ref, source: Json)(implicit subject: Subject, project: Project): RejOrResource[F] = {
    val sourceWithCtx = addContextIfEmpty(source)
    materializer(sourceWithCtx, id.value).flatMap {
      case Value(_, _, graph) => create(id, schema, sourceWithCtx, graph.removeMetadata)
    }
  }

  private def create(id: ResId, schema: Ref, source: Json, graph: Graph)(implicit
      subject: Subject,
      project: Project
  ): RejOrResource[F] =
    validate(schema, graph).flatMap { _ =>
      repo.create(id, OrganizationRef(project.organizationUuid), schema, graph.rootTypes, source)
    }

  /**
    * Updates an existing resource.
    *
    * @param id     the id of the resource
    * @param rev    the last known revision of the resource
    * @param schema the schema reference that constrains the resource
    * @param source the new source representation in json-ld format
    * @return either a rejection or the updated resource in the F context
    */
  def update(id: ResId, rev: Long, schema: Ref, source: Json)(implicit
      subject: Subject,
      project: Project
  ): RejOrResource[F] = {
    val sourceWithCtx = addContextIfEmpty(source)
    for {
      matValue <- materializer(sourceWithCtx, id.value)
      graph     = matValue.graph.removeMetadata
      _        <- validate(schema, graph)
      updated  <- repo.update(id, schema, rev, graph.rootTypes, sourceWithCtx)
    } yield updated
  }

  /**
    * Fetches the latest revision of the resources' source
    *
    * @param id     the id of the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId): RejOrSource[F] =
    repo.get(id, None).map(_.value).toRight(notFound(id.ref))

  /**
    * Fetches the provided revision of the resources' source
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long): RejOrSource[F] =
    repo.get(id, rev, None).map(_.value).toRight(notFound(id.ref, rev = Some(rev)))

  /**
    * Fetches the latest revision of the resources' source
    *
    * @param id     the id of the resource
    * @param schema the schema reference that constrains the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, schema: Ref): RejOrSource[F] =
    repo.get(id, Some(schema)).map(_.value).toRight(notFound(id.ref, schema = Some(schema)))

  /**
    * Fetches the provided tag of the resources' source
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String): RejOrSource[F] =
    repo.get(id, tag, None).map(_.value).toRight(notFound(id.ref, tag = Some(tag)))

  /**
    * Fetches the provided revision of the resources' source
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @param schema the schema reference that constrains the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long, schema: Ref): RejOrSource[F] =
    repo.get(id, rev, Some(schema)).map(_.value).toRight(notFound(id.ref, rev = Some(rev), schema = Some(schema)))

  /**
    * Fetches the provided tag of the resources' source
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @param schema theschema reference that constrains the resource
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String, schema: Ref): RejOrSource[F] =
    repo.get(id, tag, Some(schema)).map(_.value).toRight(notFound(id.ref, tag = Some(tag), schema = Some(schema)))

  /**
    * Fetches the latest revision of a resource
    *
    * @param id     the id of the resource
    * @param schema the schema reference that constrains the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId, schema: Ref)(implicit project: Project): RejOrResourceV[F] =
    fetch(id, MetadataOptions(), Some(schema))

  /**
    * Fetches the provided revision of a resource
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @param schema the schema reference that constrains the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId, rev: Long, schema: Ref)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, rev, Some(schema))
      .toRight(notFound(id.ref, rev = Some(rev), schema = Some(schema)))
      .flatMap(materializer.withMeta(_, MetadataOptions()))

  /**
    * Fetches the provided tag of a resource
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @param schema the schema reference that constrains the resource
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String, schema: Ref)(implicit project: Project): RejOrResourceV[F] =
    fetch(id, tag, MetadataOptions(), Some(schema))

  /**
    * Fetches the latest revision of a resource
    *
    * @param id     the id of the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
    fetch(id, MetadataOptions(), None)

  /**
    * Fetches the provided revision of a resource
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, rev, None)
      .toRight(notFound(id.ref, rev = Some(rev)))
      .flatMap(materializer.withMeta(_, MetadataOptions()))

  /**
    * Fetches the provided tag of a resource
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: Project): RejOrResourceV[F] =
    fetch(id, tag, MetadataOptions(), None)

  /**
    * Fetches the latest revision of a resource schema
    *
    * @param id the id of the resource
    * @return Right(schema) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSchema(id: ResId): RejOrSchema[F] =
    repo.get(id, None).map(_.schema).toRight(notFound(id.ref))

  /**
    * Fetches the latest revision of a resource
    *
    * @param id     the id of the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId, metadataOptions: MetadataOptions, schemaOpt: Option[Ref])(implicit
      project: Project
  ): RejOrResourceV[F] =
    repo
      .get(id, schemaOpt)
      .toRight(notFound(id.ref, schema = schemaOpt))
      .flatMap(materializer.withMeta(_, metadataOptions))

  /**
    * Fetches the provided tag of a resource
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @return Right(resource) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetch(id: ResId, tag: String, metadataOptions: MetadataOptions, schemaOpt: Option[Ref])(implicit
      project: Project
  ): RejOrResourceV[F] =
    repo
      .get(id, tag, schemaOpt)
      .toRight(notFound(id.ref, tag = Some(tag), schema = schemaOpt))
      .flatMap(materializer.withMeta(_, metadataOptions))

  /**
    * Deprecates an existing resource
    *
    * @param id     the id of the resource
    * @param rev    the last known revision of the resource
    * @param schema the schema reference that constrains the resource
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long, schema: Ref)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, schema, rev)

  /**
    * Lists resources for the given project and schema
    *
    * @param view       optionally available default elasticSearch view
    * @param params     filter parameters of the resources
    * @param pagination pagination options
    * @return search results in the F context
    */
  def list(view: Option[ElasticSearchView], params: SearchParams, pagination: Pagination)(implicit
      tc: HttpClient[F, JsonResults],
      elasticSearch: ElasticSearchClient[F]
  ): F[JsonResults] =
    listResources(view, params, pagination)

  /**
    * Lists incoming resources for the provided ''id''
    *
    * @param id         the resource id for which to retrieve the incoming links
    * @param view       the default sparql view
    * @param pagination pagination options
    * @return search results in the F context
    */
  def listIncoming(id: AbsoluteIri, view: SparqlView, pagination: FromPagination)(implicit
      sparql: BlazegraphClient[F]
  ): F[LinkResults] =
    view.incoming(id, pagination)

  /**
    * Lists outgoing resources for the provided ''id''
    *
    * @param id                   the resource id for which to retrieve the outgoing links
    * @param view                 the sparql view
    * @param pagination           pagination options
    * @param includeExternalLinks flag to decide whether or not to include external links (not Nexus managed) in the query result
    * @return search results in the F context
    */
  def listOutgoing(
      id: AbsoluteIri,
      view: SparqlView,
      pagination: FromPagination,
      includeExternalLinks: Boolean
  )(implicit sparql: BlazegraphClient[F]): F[LinkResults] =
    view.outgoing(id, pagination, includeExternalLinks)

  private def validate(schema: Ref, data: Graph)(implicit project: Project): EitherT[F, Rejection, Unit] = {

    def partition(set: Set[ResourceV]): (Set[ResourceV], Set[ResourceV]) =
      set.partition(_.isSchema)

    def schemaContext(): EitherT[F, Rejection, SchemaContext] =
      for {
        resourceSchema              <- materializer(schema)
        importedResources           <- materializer.imports(resourceSchema.id, resourceSchema.value.graph)
        (schemaImports, dataImports) = partition(importedResources)
      } yield SchemaContext(resourceSchema, dataImports, schemaImports)

    schema.iri match {
      case `unconstrainedSchemaUri` => EitherT.rightT(())
      case _                        =>
        schemaContext().flatMap { resolved =>
          val resolvedSchemaSets =
            resolved.schemaImports.foldLeft(resolved.schema.value.graph.triples)(_ ++ _.value.graph.triples)
          val resolvedSchema     = Graph(blank, resolvedSchemaSets).asJena
          val resolvedDataSets   = resolved.dataImports.foldLeft(data.triples)(_ ++ _.value.graph.triples)
          val resolvedData       = Graph(blank, resolvedDataSets).asJena
          toEitherT(schema, ShaclEngine(resolvedData, resolvedSchema, validateShapes = false, reportDetails = true))
        }
    }
  }

  private def addContextIfEmpty(source: Json)(implicit project: Project): Json =
    source.contextValue match {
      case `emptyJson` =>
        source deepMerge Json.obj(
          "@context" -> Json.obj("@base" -> project.base.asString.asJson, "@vocab" -> project.vocab.asString.asJson)
        )
      case _           => source
    }
}

object Resources {

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Resources]] for the provided F type
    */
  final def apply[F[_]: Repo: Effect: Materializer](implicit config: ServiceConfig): Resources[F] =
    new Resources[F]()

  final private[resources] case class SchemaContext(
      schema: ResourceV,
      dataImports: Set[ResourceV],
      schemaImports: Set[ResourceV]
  )

  def getOrAssignId(json: Json)(implicit project: Project): Either[Rejection, AbsoluteIri] =
    json.id match {
      case Right(id)                              => Right(id)
      case Left(IdRetrievalError.IdNotFound)      => Right(generateId(project.base))
      case Left(IdRetrievalError.InvalidId(id))   => Left(InvalidJsonLD(s"The @id value '$id' is not a valid Iri"))
      case Left(IdRetrievalError.Unexpected(msg)) => Left(InvalidJsonLD(msg))
    }

  private[resources] def generateId(base: AbsoluteIri): AbsoluteIri = url"${base.asString}${uuid()}"

}
