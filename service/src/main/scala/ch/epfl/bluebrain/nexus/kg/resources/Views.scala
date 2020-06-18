package ch.epfl.bluebrain.nexus.kg.resources

import java.util.UUID

import akka.actor.ActorSystem
import cats.data.{EitherT, OptionT}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClientError
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.iam.client.IamClientError
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, Caller}
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.indexing.View
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.RemoteProjectEventStream
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing.ViewEncoder._
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.Resources.generateId
import ch.epfl.bluebrain.nexus.kg.resources.Views._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.Clients._
import ch.epfl.bluebrain.nexus.kg.routes.{Clients, SearchParams}
import ch.epfl.bluebrain.nexus.kg.uuid
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Encoder, Json}

class Views[F[_]](repo: Repo[F])(implicit
    F: Effect[F],
    as: ActorSystem,
    materializer: Materializer[F],
    config: AppConfig,
    projectCache: ProjectCache[F],
    viewCache: ViewCache[F],
    clients: Clients[F]
) {

  implicit private val iamClientConfig: IamClientConfig = config.iam.iamClient

  private def rejectWhenFound: Option[Resource] => Either[Rejection, Unit] = {
    case None           => Right(())
    case Some(resource) => Left(ResourceAlreadyExists(resource.id.ref): Rejection)
  }

  /**
    * Creates a new view attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(source: Json)(implicit acls: AccessControlLists, caller: Caller, project: Project): RejOrResource[F] =
    for {

      materialized <- materializer(transformSave(source))
      (id, value)   = materialized
      resId         = Id(project.ref, id)
      _            <- EitherT(repo.get(resId, 1L, Some(viewRef)).value.map(rejectWhenFound))
      created      <- create(Id(project.ref, id), value.graph)
    } yield created

  /**
    * Creates a new view.
    *
    * @param id          the id of the view
    * @param source      the source representation in json-ld format
    * @param extractUuid flag to decide whether to extract the uuid from the payload or to generate one
    * @return either a rejection or the newly created resource in the F context
    */
  def create(
      id: ResId,
      source: Json,
      extractUuid: Boolean = false
  )(implicit acls: AccessControlLists, caller: Caller, project: Project): RejOrResource[F] = {
    val sourceUuid = if (extractUuid) extractUuidFrom(source) else uuid()
    for {
      _                 <- EitherT(repo.get(id, 1L, Some(viewRef)).value.map(rejectWhenFound))
      value             <- materializer(transformSave(source, sourceUuid), id.value)
      Value(_, _, graph) = value
      created           <- create(id, graph)
    } yield created
  }

  /**
    * Updates an existing view.
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
  )(implicit acls: AccessControlLists, caller: Caller, project: Project): RejOrResource[F] =
    for {
      curr      <- repo.get(id, Some(viewRef)).toRight(notFound(id.ref, schema = Some(viewRef)))
      matValue  <- materializer(transformSave(source, extractUuidFrom(curr.value)), id.value)
      typedGraph = addViewType(id.value, matValue.graph)
      types      = typedGraph.rootTypes
      _         <- validateShacl(typedGraph)
      view      <- viewValidation(id, typedGraph, 1L, types)
      json      <- jsonForRepo(view.encrypt)
      updated   <- repo.update(id, viewRef, rev, types, json)
      _         <- EitherT.right(viewCache.put(view))
    } yield updated

  /**
    * Deprecates an existing view.
    *
    * @param id  the id of the view
    * @param rev the last known revision of the view
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, viewRef, rev)

  /**
    * Fetches the provided revision of the view.
    *
    * @param id  the id of the view
    * @param rev the revision of the view
    * @return Some(view) in the F context when found and None in the F context when not found
    */
  def fetchView(id: ResId, rev: Long)(implicit project: Project): EitherT[F, Rejection, View] =
    for {
      resource  <- repo.get(id, rev, Some(viewRef)).toRight(notFound(id.ref, rev = Some(rev), schema = Some(viewRef)))
      resourceV <- materializer.withMeta(resource)
      view      <- EitherT.fromEither[F](View(resourceV))
    } yield view.decrypt

  /**
    * Fetches the latest revision of a view.
    *
    * @param id the id of the view
    * @return Some(view) in the F context when found and None in the F context when not found
    */
  def fetchView(id: ResId)(implicit project: Project): EitherT[F, Rejection, View] =
    for {
      resource  <- repo.get(id, Some(viewRef)).toRight(notFound(id.ref, schema = Some(viewRef)))
      resourceV <- materializer.withMeta(resource)
      view      <- EitherT.fromEither[F](View(resourceV))
    } yield view.decrypt

  /**
    * Fetches the latest revision of the view source
    *
    * @param id the id of the view
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId): RejOrSource[F] =
    repo.get(id, Some(viewRef)).map(_.value).map(transformFetchSource).toRight(notFound(id.ref, schema = Some(viewRef)))

  /**
    * Fetches the provided revision of the view source
    *
    * @param id     the id of the view
    * @param rev    the revision of the view
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, rev: Long): RejOrSource[F] =
    repo
      .get(id, rev, Some(viewRef))
      .map(_.value)
      .map(transformFetchSource)
      .toRight(notFound(id.ref, rev = Some(rev), schema = Some(viewRef)))

  /**
    * Fetches the provided tag of the view source
    *
    * @param id     the id of the view
    * @param tag    the tag of the view
    * @return Right(source) in the F context when found and Left(NotFound) in the F context when not found
    */
  def fetchSource(id: ResId, tag: String): RejOrSource[F] =
    repo
      .get(id, tag, Some(viewRef))
      .map(_.value)
      .map(transformFetchSource)
      .toRight(notFound(id.ref, tag = Some(tag), schema = Some(viewRef)))

  /**
    * Fetches the latest revision of a view.
    *
    * @param id the id of the view
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, Some(viewRef)).toRight(notFound(id.ref, schema = Some(viewRef))).flatMap(fetch)

  /**
    * Fetches the provided revision of a view
    *
    * @param id  the id of the view
    * @param rev the revision of the view
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, rev, Some(viewRef)).toRight(notFound(id.ref, Some(rev), schema = Some(viewRef))).flatMap(fetch)

  /**
    * Fetches the provided tag of a view.
    *
    * @param id  the id of the view
    * @param tag the tag of the view
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, tag, Some(viewRef)).toRight(notFound(id.ref, tag = Some(tag), schema = Some(viewRef))).flatMap(fetch)

  /**
    * Lists views on the given project
    *
    * @param view       optionally available default elasticSearch view
    * @param params     filter parameters of the resources
    * @param pagination pagination options
    * @return search results in the F context
    */
  def list(view: Option[ElasticSearchView], params: SearchParams, pagination: Pagination)(implicit
      tc: HttpClient[F, JsonResults]
  ): F[JsonResults] =
    listResources[F](view, params.copy(schema = Some(viewSchemaUri)), pagination)

  /**
    * Lists incoming resources for the provided ''id''
    *
    * @param id         the resource id for which to retrieve the incoming links
    * @param view       the default sparql view
    * @param pagination pagination options
    * @return search results in the F context
    */
  def listIncoming(id: AbsoluteIri, view: SparqlView, pagination: FromPagination): F[LinkResults] =
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
  ): F[LinkResults] =
    view.outgoing(id, pagination, includeExternalLinks)

  private def fetch(resource: Resource)(implicit project: Project): RejOrResourceV[F] =
    materializer
      .withMeta(resource)
      .map { resourceV =>
        val graph = resourceV.value.graph
        resourceV.map(_.copy(graph = graph.filter { case (_, p, _) => p.value != nxv.token.value }))
      }
      .flatMap(outputResource)

  private def create(
      id: ResId,
      graph: Graph
  )(implicit acls: AccessControlLists, project: Project, caller: Caller): RejOrResource[F] = {
    val typedGraph = addViewType(id.value, graph)
    val types      = typedGraph.rootTypes

    for {
      _       <- validateShacl(typedGraph)
      view    <- viewValidation(id, typedGraph, 1L, types)
      json    <- jsonForRepo(view.encrypt)
      created <- repo.create(id, OrganizationRef(project.organizationUuid), viewRef, types, json)
      _       <- EitherT.right(viewCache.put(view))
    } yield created
  }

  private def addViewType(id: AbsoluteIri, graph: Graph): Graph =
    Graph(id, graph.triples + ((id, rdf.tpe, nxv.View): Triple))

  private def validateShacl(data: Graph): EitherT[F, Rejection, Unit] =
    toEitherT(viewRef, ShaclEngine(data.asJena, viewSchemaModel, validateShapes = false, reportDetails = true))

  private def viewValidation(resId: ResId, graph: Graph, rev: Long, types: Set[AbsoluteIri])(implicit
      acls: AccessControlLists,
      caller: Caller
  ): EitherT[F, Rejection, View] = {
    val resource =
      ResourceF.simpleV(resId, Value(Json.obj(), Json.obj(), graph), rev = rev, types = types, schema = viewRef)
    EitherT
      .fromEither[F](View(resource))
      .flatMap[Rejection, View] {
        case es: ElasticSearchView => validateElasticSearchMappings(resId, es).map(_ => es)
        case agg: AggregateView    =>
          agg.referenced[F].flatMap[Rejection, View] {
            case v: AggregateView =>
              val viewRefs         = v.value.collect { case ViewRef(projectRef: ProjectRef, viewId) => projectRef -> viewId }
              val eitherFoundViews = viewRefs.toList.traverse {
                case (projectRef, viewId) =>
                  OptionT(viewCache.get(projectRef).map(_.find(_.id == viewId))).toRight(notFound(viewId.ref))
              }
              eitherFoundViews.map(_ => v)
            case v                => EitherT.rightT(v)
          }
        case view                  => view.referenced[F]
      }
      // $COVERAGE-OFF$
      .flatMap[Rejection, View] {
        case v: CompositeView =>
          val fetchProjectsF = v.sourcesBy[RemoteProjectEventStream].toList.traverse { source =>
            val ref = source.id.ref
            source.fetchProject[F].map(_.toRight[Rejection](ProjectRefNotFound(source.project))).recoverWith {
              // format: off
                case err: IamClientError =>
                  F.pure(Left(InvalidResourceFormat(ref, s"Wrong 'endpoint' and/or 'token' fields. Reason: ${err.message}"): Rejection))
                case err: AdminClientError =>
                  F.pure(Left(InvalidResourceFormat(ref, s"Wrong 'endpoint' and/or 'token' fields. Reason: ${err.message}"): Rejection))
                case _ =>
                  F.pure(Left(InvalidResourceFormat(ref, "Unable to validate the remote project reference"): Rejection))
                // format: on
            }
          }
          EitherT(fetchProjectsF.map(_.sequence)).map(_ => v)
        case v                => EitherT.rightT(v)
      }
    // $COVERAGE-ON$
  }

  private def validateElasticSearchMappings(resId: ResId, es: ElasticSearchView): RejOrUnit[F] =
    EitherT(es.createIndex[F].map[Either[Rejection, Unit]](_ => Right(())).recoverWith {
      case ElasticSearchClientError(_, body) => F.pure(Left(InvalidResourceFormat(resId.ref, body)))
    })

  private def jsonForRepo(view: View): EitherT[F, Rejection, Json] = {
    val graph     = view.asGraph.removeMetadata
    val errOrJson = graph.toJson(viewCtx).map(_.replaceContext(viewCtxUri)).leftMap(err => InvalidJsonLD(err))
    EitherT.fromEither[F](errOrJson)
  }

  private def transformSave(source: Json, uuidField: String = uuid())(implicit project: Project): Json = {
    val transformed          = source.addContext(viewCtxUri) deepMerge Json.obj(nxv.uuid.prefix -> Json.fromString(uuidField))
    val withMapping          = toText(transformed, nxv.mapping.prefix)
    val projectionsTransform = withMapping.hcursor
      .get[Vector[Json]](nxv.projections.prefix)
      .map { projections =>
        val pTransformed = projections.map { projection =>
          val flattened = toText(projection, nxv.mapping.prefix, nxv.context.prefix)
          val withId    = addIfMissing(flattened, "@id", generateId(project.base))
          addIfMissing(withId, nxv.uuid.prefix, UUID.randomUUID().toString)
        }
        withMapping deepMerge Json.obj(nxv.projections.prefix -> pTransformed.asJson)
      }
      .getOrElse(withMapping)

    projectionsTransform.hcursor
      .get[Vector[Json]](nxv.sources.prefix)
      .map { sources =>
        val sourceTransformed = sources.map { source =>
          val withId = addIfMissing(source, "@id", generateId(project.base))
          addIfMissing(withId, nxv.uuid.prefix, UUID.randomUUID().toString)
        }
        projectionsTransform deepMerge Json.obj(nxv.sources.prefix -> sourceTransformed.asJson)
      }
      .getOrElse(projectionsTransform)

  }

  private def addIfMissing[A: Encoder](json: Json, key: String, value: A): Json =
    if (json.hcursor.downField(key).succeeded) json else json deepMerge Json.obj(key -> value.asJson)

  private def toText(json: Json, fields: String*)                               =
    fields.foldLeft(json) { (acc, field) =>
      acc.hcursor.get[Json](field) match {
        case Right(value) if value.isObject => acc deepMerge Json.obj(field -> value.noSpaces.asJson)
        case _                              => acc
      }
    }

  private def extractUuidFrom(source: Json): String =
    source.hcursor.get[String](nxv.uuid.prefix).getOrElse(uuid())

  private def outputResource(
      originalResource: ResourceV
  )(implicit project: Project): EitherT[F, Rejection, ResourceV] = {

    def toGraph(v: View): EitherT[F, Rejection, ResourceF[Value]] = {
      val graph  = v.asGraph
      val rooted = Graph(graph.root, graph.triples ++ originalResource.metadata())
      EitherT.rightT(originalResource.copy(value = Value(originalResource.value.source, viewCtx.contextValue, rooted)))
    }
    View(originalResource).map(_.labeled[F].flatMap(toGraph)).getOrElse(EitherT.rightT(originalResource))
  }
}

object Views {

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Views]] for the provided F type
    */
  final def apply[F[_]: Effect: ProjectCache: ViewCache: Clients: Materializer](implicit
      config: AppConfig,
      as: ActorSystem,
      repo: Repo[F]
  ): Views[F] =
    new Views[F](repo)

  /**
    * Converts the inline json values
    */
  def transformFetch(json: Json): Json = {
    val withMapping = fromText(json, nxv.mapping.prefix)
    withMapping.hcursor
      .get[Vector[Json]](nxv.projections.prefix)
      .map { projections =>
        val transformed = projections.map { projection =>
          fromText(projection, nxv.mapping.prefix, nxv.context.prefix)
        }
        withMapping deepMerge Json.obj(nxv.projections.prefix -> transformed.asJson)
      }
      .getOrElse(withMapping)
  }

  private def transformFetchSource(json: Json): Json =
    transformFetch(json).removeNestedKeys(nxv.uuid.prefix, nxv.token.prefix)

  private def fromText(json: Json, fields: String*) =
    fields.foldLeft(json) { (acc, field) =>
      acc.hcursor.get[String](field).flatMap(parse) match {
        case Right(parsed) => acc deepMerge Json.obj(field -> parsed)
        case _             => acc
      }
    }
}
