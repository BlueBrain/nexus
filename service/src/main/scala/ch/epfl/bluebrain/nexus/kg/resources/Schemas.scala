package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json

class Schemas[F[_]](repo: Repo[F])(implicit F: Effect[F], materializer: Materializer[F], config: ServiceConfig) {

  /**
    * Creates a new schema attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(source: Json)(implicit subject: Subject, project: ProjectResource): RejOrResource[F] =
    materializer(source.addContext(shaclCtxUri)).flatMap {
      case (id, Value(_, _, graph)) =>
        create(Id(ProjectRef(project.uuid), id), source.addContext(shaclCtxUri), graph.removeMetadata)
    }

  /**
    * Creates a new storage.
    *
    * @param id     the id of the storage
    * @param source the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(id: ResId, source: Json)(implicit subject: Subject, project: ProjectResource): RejOrResource[F] =
    materializer(source.addContext(shaclCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, source.addContext(shaclCtxUri), graph.removeMetadata)
    }

  /**
    * Updates an existing storage.
    *
    * @param id        the id of the resource
    * @param rev       the last known revision of the resource
    * @param source    the new source representation in json-ld format
    * @return either a rejection or the updated resource in the F context
    */
  def update(id: ResId, rev: Long, source: Json)(implicit
      subject: Subject,
      project: ProjectResource
  ): RejOrResource[F] =
    for {
      matValue  <- materializer(source.addContext(shaclCtxUri), id.value)
      typedGraph = addSchemaType(matValue.graph.removeMetadata)
      types      = typedGraph.cursor.downSet(rdf.tpe).as[Set[AbsoluteIri]].getOrElse(Set.empty)
      _         <- validateShacl(id, typedGraph)
      updated   <- repo.update(id, shaclRef, rev, types, source.addContext(shaclCtxUri))
    } yield updated

  /**
    * Deprecates an existing storage.
    *
    * @param id  the id of the storage
    * @param rev the last known revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, shaclRef, rev)

  /**
    * Fetches the latest revision of the schema source
    *
    * @param id the id of the schema
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId): RejOrSource[F] =
    repo.get(id, Some(shaclRef)).map(_.value).toRight(notFound(id.ref, schema = Some(shaclRef)))

  /**
    * Fetches the provided revision of the schema source
    *
    * @param id     the id of the schema
    * @param rev    the revision of the schema
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long): RejOrSource[F] =
    repo.get(id, rev, Some(shaclRef)).map(_.value).toRight(notFound(id.ref, rev = Some(rev), schema = Some(shaclRef)))

  /**
    * Fetches the provided tag of the schema source
    *
    * @param id     the id of the schema
    * @param tag    the tag of the schema
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String): RejOrSource[F] =
    repo.get(id, tag, Some(shaclRef)).map(_.value).toRight(notFound(id.ref, tag = Some(tag), schema = Some(shaclRef)))

  /**
    * Fetches the latest revision of a storage.
    *
    * @param id the id of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId)(implicit project: ProjectResource): RejOrResourceV[F] =
    repo.get(id, Some(shaclRef)).toRight(notFound(id.ref)).flatMap(materializer.withMeta(_))

  /**
    * Fetches the provided revision of a storage
    *
    * @param id  the id of the storage
    * @param rev the revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: ProjectResource): RejOrResourceV[F] =
    repo.get(id, rev, Some(shaclRef)).toRight(notFound(id.ref, Some(rev))).flatMap(materializer.withMeta(_))

  /**
    * Fetches the provided tag of a storage.
    *
    * @param id  the id of the storage
    * @param tag the tag of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: ProjectResource): RejOrResourceV[F] =
    repo.get(id, tag, Some(shaclRef)).toRight(notFound(id.ref, tag = Some(tag))).flatMap(materializer.withMeta(_))

  /**
    * Lists schemas on the given project
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
    listResources[F](view, params.copy(schema = Some(shaclSchemaUri)), pagination)

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

  private def create(id: ResId, source: Json, graph: Graph)(implicit
      subject: Subject,
      project: ProjectResource
  ): RejOrResource[F] = {
    val typedGraph = addSchemaType(graph)
    val types      = typedGraph.cursor.downSet(rdf.tpe).as[Set[AbsoluteIri]].getOrElse(Set.empty)

    for {
      _        <- validateShacl(id, typedGraph)
      resource <- repo.create(id, OrganizationRef(project.value.organizationUuid), shaclRef, types, source)
    } yield resource
  }

  private def addSchemaType(graph: Graph): Graph =
    graph.append(rdf.tpe, nxv.Schema)

  private def validateShacl(resId: ResId, data: Graph)(implicit project: ProjectResource): EitherT[F, Rejection, Unit] =
    materializer.imports(resId, data).flatMap { resolved =>
      val resolvedSets = resolved.foldLeft(data.triples)(_ ++ _.value.graph.triples)
      val resolvedData = Graph(data.root, resolvedSets).asJena
      toEitherT(shaclRef, ShaclEngine(resolvedData, reportDetails = true))
    }
}

object Schemas {

  final def apply[F[_]: Effect: Materializer](repo: Repo[F])(implicit config: ServiceConfig): Schemas[F] =
    new Schemas[F](repo)
}
