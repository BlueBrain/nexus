package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.cache.StorageCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.KgConfig
import ch.epfl.bluebrain.nexus.kg.config.KgConfig.StorageConfig
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.Storages.TimedStorage
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.Verify
import ch.epfl.bluebrain.nexus.kg.storage.StorageEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json

class Storages[F[_]](repo: Repo[F])(implicit
    F: Effect[F],
    materializer: Materializer[F],
    config: ServiceConfig,
    cache: StorageCache[F]
) {

  implicit private val kgConfig: KgConfig = config.kg

  /**
    * Creates a new storage attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(source: Json)(implicit subject: Subject, verify: Verify[F], project: Project): RejOrResource[F] =
    materializer(source.addContext(storageCtxUri)).flatMap {
      case (id, Value(_, _, graph)) => create(Id(project.ref, id), graph)
    }

  /**
    * Creates a new storage.
    *
    * @param id     the id of the storage
    * @param source the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(
      id: ResId,
      source: Json
  )(implicit subject: Subject, verify: Verify[F], project: Project): RejOrResource[F] =
    materializer(source.addContext(storageCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, graph)
    }

  /**
    * Updates an existing storage.
    *
    * @param id        the id of the resource
    * @param rev       the last known revision of the resource
    * @param source    the new source representation in json-ld format
    * @return either a rejection or the updated resource in the F context
    */
  def update(
      id: ResId,
      rev: Long,
      source: Json
  )(implicit subject: Subject, verify: Verify[F], project: Project): RejOrResource[F] =
    for {
      matValue  <- materializer(source.addContext(storageCtxUri), id.value)
      typedGraph = addStorageType(matValue.graph)
      types      = typedGraph.rootTypes
      _         <- validateShacl(typedGraph)
      storage   <- storageValidation(id, typedGraph, 1L, types)
      json      <- jsonForRepo(storage.encrypt)
      updated   <- repo.update(id, storageRef, rev, types, json)
      _         <- EitherT.right(cache.put(storage)(updated.updated))
    } yield updated

  /**
    * Deprecates an existing storage.
    *
    * @param id  the id of the storage
    * @param rev the last known revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, storageRef, rev)

  /**
    * Fetches the latest revision of a storage.
    *
    * @param id the id of the resolver
    * @return Some(storage) in the F context when found and None in the F context when not found
    */
  def fetchStorage(id: ResId)(implicit project: Project, config: StorageConfig): EitherT[F, Rejection, TimedStorage] = {
    val repoOrNotFound = repo.get(id, Some(storageRef)).toRight(notFound(id.ref, schema = Some(storageRef)))
    repoOrNotFound.flatMap(fetch(_, dropKeys = false)).subflatMap(r => Storage(r).map(_.decrypt -> r.updated))
  }

  /**
    * Fetches the latest revision of the storage source
    *
    * @param id the id of the storage
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId): RejOrSource[F] =
    repo
      .get(id, Some(storageRef))
      .map(_.value)
      .map(removeSecretsAndAlgorithm)
      .toRight(notFound(id.ref, schema = Some(storageRef)))

  /**
    * Fetches the provided revision of the storage source
    *
    * @param id     the id of the storage
    * @param rev    the revision of the storage
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long): RejOrSource[F] =
    repo
      .get(id, rev, Some(storageRef))
      .map(_.value)
      .map(removeSecretsAndAlgorithm)
      .toRight(notFound(id.ref, rev = Some(rev), schema = Some(storageRef)))

  /**
    * Fetches the provided tag of the storage source
    *
    * @param id     the id of the storage
    * @param tag    the tag of the storage
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String): RejOrSource[F] =
    repo
      .get(id, tag, Some(storageRef))
      .map(_.value)
      .map(removeSecretsAndAlgorithm)
      .toRight(notFound(id.ref, tag = Some(tag), schema = Some(storageRef)))

  private def removeSecretsAndAlgorithm(json: Json): Json =
    json.removeKeys("credentials", "accessKey", "secretKey", nxv.algorithm.prefix)

  /**
    * Fetches the latest revision of a storage.
    *
    * @param id the id of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, Some(storageRef))
      .toRight(notFound(id.ref, schema = Some(storageRef)))
      .flatMap(fetch(_, dropKeys = true))

  /**
    * Fetches the provided revision of a storage
    *
    * @param id  the id of the storage
    * @param rev the revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, rev, Some(storageRef))
      .toRight(notFound(id.ref, Some(rev), schema = Some(storageRef)))
      .flatMap(fetch(_, dropKeys = true))

  /**
    * Fetches the provided tag of a storage.
    *
    * @param id  the id of the storage
    * @param tag the tag of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, tag, Some(storageRef))
      .toRight(notFound(id.ref, tag = Some(tag), schema = Some(storageRef)))
      .flatMap(fetch(_, dropKeys = true))

  /**
    * Lists storages on the given project
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
    listResources(view, params.copy(schema = Some(storageSchemaUri)), pagination)

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
    * @param view                 the default sparql view
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

  private def fetch(resource: Resource, dropKeys: Boolean)(implicit project: Project): RejOrResourceV[F] =
    materializer.withMeta(resource).map { resourceV =>
      val graph      = resourceV.value.graph
      val filter     = Set[IriNode](nxv.accessKey, nxv.secretKey, nxv.credentials)
      val finalGraph = if (dropKeys) graph.filter { case (_, p, _) => !filter.contains(p) }
      else graph
      resourceV.map(_.copy(graph = finalGraph))
    }

  private def create(
      id: ResId,
      graph: Graph
  )(implicit subject: Subject, project: Project, verify: Verify[F]): RejOrResource[F] = {
    val typedGraph = addStorageType(graph)
    val types      = typedGraph.rootTypes

    for {
      _       <- validateShacl(typedGraph)
      storage <- storageValidation(id, typedGraph, 1L, types)
      json    <- jsonForRepo(storage.encrypt)
      created <- repo.create(id, OrganizationRef(project.organizationUuid), storageRef, types, json)
      _       <- EitherT.right(cache.put(storage)(created.updated))
    } yield created
  }

  private def addStorageType(graph: Graph): Graph =
    graph.append(rdf.tpe, nxv.Storage)

  private def validateShacl(data: Graph): EitherT[F, Rejection, Unit] =
    toEitherT(storageRef, ShaclEngine(data.asJena, storageSchemaModel, validateShapes = false, reportDetails = true))

  private def storageValidation(resId: ResId, graph: Graph, rev: Long, types: Set[AbsoluteIri])(implicit
      verify: Verify[F]
  ): EitherT[F, Rejection, Storage] = {
    val resource =
      ResourceF.simpleV(resId, Value(Json.obj(), Json.obj(), graph), rev = rev, types = types, schema = storageRef)

    EitherT.fromEither[F](Storage(resource)).flatMap { storage =>
      EitherT(storage.isValid.apply).map(_ => storage).leftMap(msg => InvalidResourceFormat(resId.value.ref, msg))
    }
  }

  private def jsonForRepo(storage: Storage): EitherT[F, Rejection, Json] = {
    val graph     = storage.asGraph.removeMetadata
    val errOrJson = graph.toJson(storageCtx).map(_.replaceContext(storageCtxUri)).leftMap(err => InvalidJsonLD(err))
    EitherT.fromEither[F](errOrJson)
  }
}

object Storages {

  type TimedStorage = (Storage, Instant)

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Storages]] for the provided F type
    */
  final def apply[F[_]: Effect: Materializer](implicit
      config: ServiceConfig,
      repo: Repo[F],
      cache: StorageCache[F]
  ): Storages[F] =
    new Storages[F](repo)
}
