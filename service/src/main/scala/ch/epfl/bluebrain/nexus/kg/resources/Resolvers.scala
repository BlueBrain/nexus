package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Caller
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ResolverCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.kg.resolve.ResolverEncoder._
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, Resolver}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import io.circe.Json

class Resolvers[F[_]](repo: Repo[F])(implicit
    F: Effect[F],
    materializer: Materializer[F],
    config: AppConfig,
    projectCache: ProjectCache[F],
    resolverCache: ResolverCache[F]
) {

  implicit private val iamClientConfig: IamClientConfig = config.iam.iamClient

  /**
    * Creates a new resolver attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(source: Json)(implicit caller: Caller, project: Project): RejOrResource[F] =
    materializer(source.addContext(resolverCtxUri)).flatMap {
      case (id, Value(_, _, graph)) => create(Id(project.ref, id), graph)
    }

  /**
    * Creates a new resolver.
    *
    * @param id     the id of the resolver
    * @param source the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(id: ResId, source: Json)(implicit caller: Caller, project: Project): RejOrResource[F] =
    materializer(source.addContext(resolverCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, graph)
    }

  /**
    * Updates an existing resolver.
    *
    * @param id        the id of the resource
    * @param rev       the last known revision of the resource
    * @param source    the new source representation in json-ld format
    * @return either a rejection or the updated resource in the F context
    */
  def update(id: ResId, rev: Long, source: Json)(implicit caller: Caller, project: Project): RejOrResource[F] =
    for {
      matValue  <- materializer(source.addContext(resolverCtxUri), id.value)
      typedGraph = addResolverType(matValue.graph)
      types      = typedGraph.cursor.downSet(rdf.tpe).as[Set[AbsoluteIri]].getOrElse(Set.empty)
      _         <- validateShacl(typedGraph)
      resolver  <- resolverValidation(id, typedGraph, 1L, types)
      json      <- jsonForRepo(resolver)
      updated   <- repo.update(id, resolverRef, rev, types, json)
      _         <- EitherT.right(resolverCache.put(resolver))

    } yield updated

  /**
    * Deprecates an existing resolver.
    *
    * @param id  the id of the resolver
    * @param rev the last known revision of the resolver
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, resolverRef, rev)

  /**
    * Fetches the latest revision of a resolver.
    *
    * @param id the id of the resolver
    * @return Right(resolver) in the F context when found and Left(notFound) in the F context when not found
    */
  def fetchResolver(id: ResId)(implicit project: Project): EitherT[F, Rejection, Resolver] =
    for {
      resource  <- repo.get(id, Some(resolverRef)).toRight(notFound(id.ref, schema = Some(resolverRef)))
      resourceV <- materializer.withMeta(resource)
      resolver  <- EitherT.fromEither[F](Resolver(resourceV))
    } yield resolver

  /**
    * Fetches the latest revision of the resolver source
    *
    * @param id the id of the resolver
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId): RejOrSource[F] =
    repo.get(id, Some(resolverRef)).map(_.value).toRight(notFound(id.ref, schema = Some(resolverRef)))

  /**
    * Fetches the provided revision of the resolver source
    *
    * @param id     the id of the resolver
    * @param rev    the revision of the resolver
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long): RejOrSource[F] =
    repo
      .get(id, rev, Some(resolverRef))
      .map(_.value)
      .toRight(notFound(id.ref, rev = Some(rev), schema = Some(resolverRef)))

  /**
    * Fetches the provided tag of the resolver source
    *
    * @param id     the id of the resolver
    * @param tag    the tag of the resolver
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String): RejOrSource[F] =
    repo
      .get(id, tag, Some(resolverRef))
      .map(_.value)
      .toRight(notFound(id.ref, tag = Some(tag), schema = Some(resolverRef)))

  /**
    * Fetches the latest revision of a resolver.
    *
    * @param id the id of the resolver
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, Some(resolverRef)).toRight(notFound(id.ref, schema = Some(resolverRef))).flatMap(fetch)

  /**
    * Fetches the provided revision of a resolver
    *
    * @param id  the id of the resolver
    * @param rev the revision of the resolver
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, rev, Some(resolverRef)).toRight(notFound(id.ref, Some(rev), schema = Some(resolverRef))).flatMap(fetch)

  /**
    * Fetches the provided tag of a resolver.
    *
    * @param id  the id of the resolver
    * @param tag the tag of the resolver
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: Project): RejOrResourceV[F] =
    repo
      .get(id, tag, Some(resolverRef))
      .toRight(notFound(id.ref, tag = Some(tag), schema = Some(resolverRef)))
      .flatMap(fetch)

  /**
    * Fetches the provided resource from the resolution process.
    *
    * @param id  the id of the resource
    * @param rev the revision of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: AbsoluteIri, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    materializer(Ref.Revision(id, rev), includeMetadata = true)

  /**
    * Fetches the provided resource from the resolution process.
    *
    * @param id  the id of the resource
    * @param tag the tag of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: AbsoluteIri, tag: String)(implicit project: Project): RejOrResourceV[F] =
    materializer(Ref.Tag(id, tag), includeMetadata = true)

  /**
    * Fetches the provided resource from the resolution process.
    *
    * @param id  the id of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: AbsoluteIri)(implicit project: Project): RejOrResourceV[F] =
    materializer(id.ref, includeMetadata = true)

  /**
    * Fetches the provided resource from the resolution process using the provided resolver.
    *
    * @param id         the id of the resolver
    * @param resourceId the id of the resource
    * @param rev        the revision of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: ResId, resourceId: AbsoluteIri, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    fetchResolver(id).flatMap(materializer(Ref.Revision(resourceId, rev), _, includeMetadata = true))

  /**
    * Fetches the provided resource from the resolution process using the provided resolver.
    *
    * @param id         the id of the resolver
    * @param resourceId the id of the resource
    * @param tag        the tag of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: ResId, resourceId: AbsoluteIri, tag: String)(implicit project: Project): RejOrResourceV[F] =
    fetchResolver(id).flatMap(materializer(Ref.Tag(resourceId, tag), _, includeMetadata = true))

  /**
    * Fetches the provided resource from the resolution process using the provided resolver.
    *
    * @param id         the id of the resolver
    * @param resourceId the id of the resource
    * @return Right(resource) in the F context when found and Left(notFound) in the F context when not found
    */
  def resolve(id: ResId, resourceId: AbsoluteIri)(implicit project: Project): RejOrResourceV[F] =
    fetchResolver(id).flatMap(materializer(resourceId.ref, _, includeMetadata = true))

  /**
    * Lists resolvers on the given project
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
    listResources(view, params.copy(schema = Some(resolverSchemaUri)), pagination)

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

  private def fetch(resource: Resource)(implicit project: Project): RejOrResourceV[F] =
    materializer.withMeta(resource).flatMap(outputResource)

  private def create(id: ResId, graph: Graph)(implicit caller: Caller, project: Project): RejOrResource[F] = {
    val typedGraph = addResolverType(graph)
    val types      = typedGraph.rootTypes
    for {
      _        <- validateShacl(typedGraph)
      resolver <- resolverValidation(id, typedGraph, 1L, types)
      json     <- jsonForRepo(resolver)
      created  <- repo.create(id, OrganizationRef(project.organizationUuid), resolverRef, types, json)
      _        <- EitherT.right(resolverCache.put(resolver))

    } yield created
  }

  private def addResolverType(graph: Graph): Graph =
    graph.append(rdf.tpe, nxv.Resolver)

  private def validateShacl(data: Graph): EitherT[F, Rejection, Unit] =
    toEitherT(resolverRef, ShaclEngine(data.asJena, resolverSchemaModel, validateShapes = false, reportDetails = true))

  private def resolverValidation(resId: ResId, graph: Graph, rev: Long, types: Set[AbsoluteIri])(implicit
      caller: Caller
  ): EitherT[F, Rejection, Resolver] = {

    val resource =
      ResourceF.simpleV(resId, Value(Json.obj(), Json.obj(), graph), rev = rev, types = types, schema = resolverRef)

    EitherT.fromEither[F](Resolver(resource)).flatMap {
      case r: CrossProjectResolver if r.identities.forall(caller.identities.contains) => r.referenced[F]
      case _: CrossProjectResolver                                                    => EitherT.leftT[F, Resolver](InvalidIdentity())
      case r: InProjectResolver                                                       => EitherT.rightT(r)
    }
  }

  private def jsonForRepo(resolver: Resolver): EitherT[F, Rejection, Json] = {
    val graph                = resolver.asGraph.removeMetadata
    val jsonOrMarshallingErr = graph.toJson(resolverCtx).map(_.replaceContext(resolverCtxUri))
    jsonOrMarshallingErr match {
      case Left(err)    =>
        EitherT(
          F.raiseError[Either[Rejection, Json]](
            KgError.InternalError(s"Unexpected MarshallingError with message '$err'")
          )
        )
      case Right(value) => EitherT.rightT(value)
    }
  }

  private def outputResource(originalResource: ResourceV)(implicit project: Project): EitherT[F, Rejection, ResourceV] =
    Resolver(originalResource) match {
      case Right(resolver) =>
        resolver.labeled.flatMap { labeledResolver =>
          val graph = labeledResolver.asGraph
          val value =
            Value(
              originalResource.value.source,
              resolverCtx.contextValue,
              graph ++ originalResource.metadata()
            )
          EitherT.rightT(originalResource.copy(value = value))
        }
      case _               => EitherT.rightT(originalResource)
    }
}

object Resolvers {

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Resolvers]] for the provided F type
    */
  final def apply[F[_]: Effect: ProjectCache: Materializer](implicit
      config: AppConfig,
      repo: Repo[F],
      cache: ResolverCache[F]
  ): Resolvers[F] =
    new Resolvers[F](repo)
}
