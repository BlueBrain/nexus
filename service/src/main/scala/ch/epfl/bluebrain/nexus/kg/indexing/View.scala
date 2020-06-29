package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.Instant
import java.util.regex.Pattern.quote
import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.effect.{Effect, Timer}
import cats.implicits._
import cats.{Functor, Monad}
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.config.AdminClientConfig
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.commons.test.Resources._
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.config.KgConfig._
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.kg.indexing.View.{query, read, AggregateView, CompositeView, ViewRef}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Interval, Projection, Source}
import ch.epfl.bluebrain.nexus.kg.indexing.View.SparqlView._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.metaKeys
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.kg.{identities, KgError}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{Cursor, Graph, GraphDecoder, NonEmptyString}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import com.typesafe.scalalogging.Logger
import io.circe.Json
import org.apache.jena.query.QueryFactory

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * Enumeration of view types.
  */
sealed trait View extends Product with Serializable { self =>

  /**
    * @return a reference to the project that the view belongs to
    */
  def ref: ProjectRef

  /**
    * @return the user facing view id
    */
  def id: AbsoluteIri

  /**
    * @return the underlying uuid generated for this view
    */
  def uuid: UUID

  /**
    * @return the view revision
    */
  def rev: Long

  /**
    * @return the deprecation state of the view
    */
  def deprecated: Boolean

  /**
    * @return a generated name that uniquely identifies the view and its current revision
    */
  def name: String =
    s"${ref.id}_${uuid}_$rev"

  /**
    * Converts the ProjectRefs into ProjectLabels when found on the cache
    */
  def labeled[F[_]: Monad](implicit projectCache: ProjectCache[F]): EitherT[F, Rejection, View] =
    this match {
      case v: AggregateView =>
        v.value.toList.traverse { case ViewRef(project, id) => project.toLabel[F].map(ViewRef(_, id)) }.map(v.make)
      case v: CompositeView =>
        val labeledSources: EitherT[F, Rejection, List[Source]] = v.sources.toList.traverse {
          case source: CrossProjectEventStream => source.project.toLabel[F].map(label => source.copy(project = label))
          case source                          => EitherT.rightT(source)
        }
        labeledSources.map(sources => v.copy(sources = sources.toSet))
      case other            => EitherT.rightT(other)
    }

  /**
    * Converts the ProjectLabels into ProjectRefs when found on the cache
    * respecting the views/query and resources/read permissions
    */
  def referenced[F[_]: Monad](implicit
      projectCache: ProjectCache[F],
      acls: AccessControlLists,
      caller: Caller
  ): EitherT[F, Rejection, View] =
    this match {
      case v: AggregateView =>
        v.value.toList
          .traverse {
            case ViewRef(project, id) => project.toRef[F](query, caller.identities).map(ViewRef(_, id))
          }
          .map(v.make)
      case v: CompositeView =>
        val labeledSources: EitherT[F, Rejection, List[Source]] = v.sources.toList.traverse {
          case source: CrossProjectEventStream =>
            source.project.toRef[F](read, source.identities).map(label => source.copy(project = label))
          case source                          => EitherT.rightT(source)
        }
        labeledSources.map(sources => v.copy(sources = sources.toSet))
      case other            => EitherT.rightT(other)
    }

  /**
    * @return a new view with the same values as the current but encrypting the sensitive information
    */
  def encrypt(implicit config: CompositeViewConfig): View =
    self match {
      case view: CompositeView =>
        view.copy(sources = view.sources.iterator.map {
          case s: RemoteProjectEventStream => s.copy(token = s.token.map(t => t.copy(t.value.encrypt)))
          case other                       => other
        }.toSet)
      case rest                => rest
    }

  /**
    * @return a new view with the same values as the current but decrypting the sensitive information
    */
  def decrypt(implicit config: CompositeViewConfig): View =
    self match {
      case view: CompositeView =>
        view.copy(sources = view.sources.iterator.map {
          case s: RemoteProjectEventStream => s.copy(token = s.token.map(t => t.copy(t.value.decrypt)))
          case other                       => other
        }.toSet)
      case rest                => rest
    }
}

object View {

  val read: Permission     = Permission.unsafe("resources/read")
  val query: Permission    = Permission.unsafe("views/query")
  val write: Permission    = Permission.unsafe("views/write")
  private val idTemplating = "{resource_id}"

  /**
    * @param resourceSchemas set of schemas iris used in the view. Indexing will be triggered only for resources validated against any of those schemas (when empty, all resources are indexed)
    * @param resourceTypes set of types iris used in the view. Indexing will be triggered only for resources containing any of those types (when empty, all resources are indexed)
    * @param resourceTag tag used in the view. Indexing will be triggered only for resources containing the provided tag
    * @param includeDeprecated flag to include or exclude the deprecated resources on the indexed Document
    */
  final case class Filter(
      resourceSchemas: Set[AbsoluteIri] = Set.empty,
      resourceTypes: Set[AbsoluteIri] = Set.empty,
      resourceTag: Option[String] = None,
      includeDeprecated: Boolean = true
  )

  object Filter {
    implicit final val filterGraphDecoder: GraphDecoder[Filter]                   = GraphDecoder.instance { c =>
      for {
        schemas    <- c.downSet(nxv.resourceSchemas).as[Set[AbsoluteIri]]
        types      <- c.downSet(nxv.resourceTypes).as[Set[AbsoluteIri]]
        tag        <- c.down(nxv.resourceTag).option[NonEmptyString].map(_.map(_.asString))
        includeDep <- c.down(nxv.includeDeprecated).withDefault(true)
      } yield Filter(schemas, types, tag, includeDep)
    }
    final def apply(res: ResourceV): Either[Rejection, Filter]                    =
      apply(res, res.value.graph.cursor)
    final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, Filter] =
      filterGraphDecoder(cursor).leftRejectionFor(res.id.ref)
  }

  sealed trait FilteredView extends View {

    /**
      * @return filters the data to be indexed
      */
    def filter: Filter

    /**
      * Retrieves the latest state of the passed resource, according to the view filter
      *
      * @param resources the resources operations
      * @param event     the event
      * @return Some(resource) if found, None otherwise, wrapped in the effect type ''F[_]''
      */
    def toResource[F[_]: Functor](
        resources: Resources[F],
        event: Event
    )(implicit project: Project, metadataOpts: MetadataOptions): F[Option[ResourceV]] =
      filter.resourceTag
        .filter(_.trim.nonEmpty)
        .map(resources.fetch(event.id, _, metadataOpts, None))
        .getOrElse(resources.fetch(event.id, metadataOpts, None))
        .toOption
        .value

    /**
      * Evaluates if the provided resource has some of the types defined on the view filter.
      *
      * @param resource the resource
      */
    def allowedTypes(resource: ResourceV): Boolean =
      filter.resourceTypes.isEmpty || filter.resourceTypes.intersect(resource.types).nonEmpty

    /**
      * Evaluates if the provided resource has some of the schemas defined on the view filter.
      *
      * @param resource the resource
      */
    def allowedSchemas(resource: ResourceV): Boolean =
      filter.resourceSchemas.isEmpty || filter.resourceSchemas.contains(resource.schema.iri)

    /**
      * Evaluates if the provided resource has the tag defined on the view filter.
      *
      * @param resource the resource
      */
    def allowedTag(resource: ResourceV): Boolean =
      filter.resourceTag.forall(tag => resource.tags.get(tag).contains(resource.rev))
  }

  /**
    * Enumeration of indexed view types.
    */
  sealed trait IndexedView extends View {

    /**
      * The progress id for this view
      */
    def progressId(implicit config: ServiceConfig): String
  }

  /**
    * Enumeration of multiple view types.
    */
  sealed trait AggregateView extends View {

    /**
      * @return the set of views that this view connects to when performing searches
      */
    def value: Set[ViewRef]

    @SuppressWarnings(Array("RepeatedCaseBody"))
    def make(newValue: List[ViewRef]): AggregateView =
      this match {
        case agg: AggregateElasticSearchView => agg.copy(value = newValue.toSet)
        case agg: AggregateSparqlView        => agg.copy(value = newValue.toSet)
      }

    /**
      * Return the views with ''views/query'' permissions that are not deprecated from the provided ''viewRefs''
      *
      */
    def queryableViews[F[_], T <: View: ClassTag](implicit
        projectCache: ProjectCache[F],
        F: Monad[F],
        viewCache: ViewCache[F],
        caller: Caller,
        acls: AccessControlLists
    ): F[Set[T]] =
      value.toList.foldM(Set.empty[T]) {
        case (acc, ViewRef(ref: ProjectRef, id)) =>
          (viewCache.getBy[T](ref, id) -> projectCache.getLabel(ref)).mapN {
            case (Some(view), Some(label)) if !view.deprecated && caller.hasPermission(acls, label, query) => acc + view
            case _                                                                                         => acc
          }
        case (acc, _)                            => F.pure(acc)
      }
  }

  /**
    * Enumeration of single view types.
    */
  sealed trait SingleView extends IndexedView with FilteredView {

    /**
      * The index value for this view
      */
    def index(implicit config: ServiceConfig): String

    /**
      * Attempts to create an index.
      */
    def createIndex[F[_]: Effect](implicit config: ServiceConfig, clients: Clients[F]): F[Unit]

    /**
      * Attempts to delete an index.
      */
    def deleteIndex[F[_]](implicit config: ServiceConfig, clients: Clients[F]): F[Boolean]

    /**
      * Attempts to delete the resource on the view index.
      *
      * @param resId the resource id to be deleted from the current index
      */
    def deleteResource[F[_]: Monad](resId: ResId)(implicit clients: Clients[F], config: ServiceConfig): F[Unit]

  }

  /**
    * Attempts to transform the resource into a [[ch.epfl.bluebrain.nexus.kg.indexing.View]].
    *
    * @param res a materialized resource
    * @return Right(view) if the resource is compatible with a View, Left(rejection) otherwise
    */
  final def apply(res: ResourceV)(implicit config: CompositeViewConfig): Either[Rejection, View] = {
    val c = res.value.graph.cursor

    def composite(): Either[Rejection, View] = {

      def validateRebuild(rebuildInterval: Option[FiniteDuration]): Either[Rejection, Unit] =
        if (rebuildInterval.forall(_ >= config.minIntervalRebuild))
          Right(())
        else
          Left(
            InvalidResourceFormat(res.id.ref, s"Rebuild interval cannot be smaller than '${config.minIntervalRebuild}'")
          )

      def projections(cursors: Set[Cursor]): Either[Rejection, Set[Projection]] =
        if (cursors.isEmpty)
          Left(InvalidResourceFormat(res.id.ref, "At least one projection must be present"))
        else if (cursors.size > config.maxProjections)
          Left(InvalidResourceFormat(res.id.ref, s"The number of projections cannot exceed ${config.maxProjections}"))
        else {
          import alleycats.std.set._
          cursors.foldM(Set.empty[Projection]) {
            case (acc, cursor) =>
              cursor.down(rdf.tpe).as[AbsoluteIri].leftRejectionFor(res.id.ref).flatMap {
                case tpe if tpe == nxv.ElasticSearchProjection.value =>
                  ElasticSearchProjection(res, cursor).map(p => acc + p)
                case tpe if tpe == nxv.SparqlProjection.value        => SparqlProjection(res, cursor).map(p => acc + p)
                case tpe                                             =>
                  val err = s"projection @type with value '$tpe' is not supported."
                  Left(InvalidResourceFormat(res.id.ref, err): Rejection)
              }
          }
        }

      def currentProjectSource(c: Cursor): Either[Rejection, Source] =
        for {
          id     <- c.as[AbsoluteIri].onError(res.id.ref, "@id")
          filter <- Filter(res, c)
        } yield ProjectEventStream(id, filter)

      // format: off
      def crossProjectSource(c: Cursor): Either[Rejection, Source] =
        for {
          id                  <- c.as[AbsoluteIri].onError(res.id.ref, "@id")
          filter              <- Filter(res, c)
          projectIdentifier   <- c.down(nxv.project).as[ProjectIdentifier].onError(id.ref, "project")
          idCursors            = c.downSet(nxv.identities).cursors.getOrElse(Set.empty)
          ids                 <- identities(res.id, idCursors)
        } yield CrossProjectEventStream(id, filter, projectIdentifier, ids)
      // format: on

      // format: off
      def remoteProjectSource(c: Cursor): Either[Rejection, Source] =
        for {
          id            <- c.as[AbsoluteIri].onError(res.id.ref, "@id")
          filter        <- Filter(res, c)
          // TODO: There should be some validation against the admin endpoint that this project actually exists.
          // This will also validate that the token is correct and has the right permissions
          projectLabel  <- c.down(nxv.project).as[ProjectLabel].onError(id.ref, "project")
          endpoint      <- c.down(nxv.endpoint).as[AbsoluteIri].onError(id.ref, "endpoint")
          token         <- c.down(nxv.token).as[Option[String]].onError(id.ref, "token")
        } yield RemoteProjectEventStream(id, filter, projectLabel, endpoint, token.map(AccessToken.apply))
      // format: on

      def sources(cursors: Set[Cursor]): Either[Rejection, Set[Source]] =
        if (cursors.isEmpty)
          Left(InvalidResourceFormat(res.id.ref, "At least one source must be present"))
        else if (cursors.size > config.maxSources)
          Left(InvalidResourceFormat(res.id.ref, s"The number of sources cannot exceed ${config.maxSources}"))
        else {
          import alleycats.std.set._
          cursors.foldM(Set.empty[Source]) { (acc, cursor) =>
            cursor.down(rdf.tpe).as[AbsoluteIri].onError(res.id.ref, "@type").flatMap {
              case tpe if tpe == nxv.ProjectEventStream.value       => currentProjectSource(cursor).map(s => acc + s)
              case tpe if tpe == nxv.CrossProjectEventStream.value  => crossProjectSource(cursor).map(s => acc + s)
              case tpe if tpe == nxv.RemoteProjectEventStream.value => remoteProjectSource(cursor).map(s => acc + s)
              case tpe                                              =>
                val err = s"sources @type with value '$tpe' is not supported."
                Left(InvalidResourceFormat(res.id.ref, err): Rejection)
            }
          }
        }

      // format: off
      for {
        uuid          <- c.down(nxv.uuid).as[UUID].onError(res.id.ref, nxv.uuid.prefix)
        pCursors       = c.downSet(nxv.projections).cursors.getOrElse(Set.empty)
        projections   <- projections(pCursors)
        sCursors       = c.downSet(nxv.sources).cursors.getOrElse(Set.empty)
        sources       <- sources(sCursors)
        rebuildCursor  = c.down(nxv.rebuildStrategy)
        rebuildTpe    <- rebuildCursor.down(rdf.tpe).as[Option[AbsoluteIri]].onError(res.id.ref, "@type")
        _             <- if(rebuildTpe.contains(nxv.Interval.value) || rebuildTpe.isEmpty)  Right(()) else Left(InvalidResourceFormat(res.id.ref, s"rebuildStrategy @type with value '$rebuildTpe' is not supported."))
        interval      <- rebuildCursor.down(nxv.value).as[Option[FiniteDuration]].onError(res.id.ref, "value")
        _             <- validateRebuild(interval)
      } yield CompositeView (sources, projections, interval.map(Interval), res.id.parent, res.id.value, uuid, res.rev, res.deprecated)
      // format: on
    }

    if (Set(nxv.View.value, nxv.ElasticSearchView.value).subsetOf(res.types)) ElasticSearchView(res)
    else if (Set(nxv.View.value, nxv.SparqlView.value).subsetOf(res.types)) SparqlView(res)
    else if (Set(nxv.View.value, nxv.CompositeView.value, nxv.Beta.value).subsetOf(res.types)) composite()
    else if (Set(nxv.View.value, nxv.AggregateElasticSearchView.value).subsetOf(res.types))
      AggregateElasticSearchView(res)
    else if (Set(nxv.View.value, nxv.AggregateSparqlView.value).subsetOf(res.types)) AggregateSparqlView(res)
    else Left(InvalidResourceFormat(res.id.ref, "The provided @type do not match any of the view types"))
  }

  /**
    * ElasticSearch specific view.
    *
    * @param mapping         the ElasticSearch mapping for the index
    * @param filter          the filtering options for the view
    * @param includeMetadata flag to include or exclude metadata on the indexed Document
    * @param sourceAsText    flag to include or exclude the source Json as a blob
    * @param ref             a reference to the project that the view belongs to
    * @param id              the user facing view id
    * @param uuid            the underlying uuid generated for this view
    * @param rev             the view revision
    * @param deprecated      the deprecation state of the view
    */
  final case class ElasticSearchView(
      mapping: Json,
      filter: Filter,
      includeMetadata: Boolean,
      sourceAsText: Boolean,
      ref: ProjectRef,
      id: AbsoluteIri,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean
  ) extends SingleView {

    private[indexing] val ctx: Json = jsonContentOf("/elasticsearch/default-context.json")
    private val settings: Json      = jsonContentOf("/elasticsearch/settings.json")

    def index(implicit config: ServiceConfig): String = s"${config.kg.elasticSearch.indexPrefix}_$name"

    def progressId(implicit config: ServiceConfig): String = s"elasticSearch-indexer-$index"

    def createIndex[F[_]](implicit F: Effect[F], config: ServiceConfig, clients: Clients[F]): F[Unit] =
      clients.elasticSearch
        .createIndex(index, settings)
        .flatMap(_ => clients.elasticSearch.updateMapping(index, mapping))
        .flatMap {
          case true  => F.unit
          case false => F.raiseError(KgError.InternalError("View mapping validation could not be performed"))
        }

    def deleteIndex[F[_]](implicit config: ServiceConfig, clients: Clients[F]): F[Boolean] =
      clients.elasticSearch.deleteIndex(index)

    def deleteResource[F[_]](
        resId: ResId
    )(implicit F: Monad[F], clients: Clients[F], config: ServiceConfig): F[Unit] = {
      val client = clients.elasticSearch.withRetryPolicy(config.kg.elasticSearch.indexing.retry)
      client.delete(index, resId.value.asString) >> F.unit
    }

    /**
      * Attempts to convert the passed resource to an ElasticSearch Document to be indexed.
      * The resulting document will have different Json shape depending on the view configuration.
      *
      * @param res the resource
      * @return Some(document) if the conversion was successful, None otherwise
      */
    def toDocument(
        res: ResourceV
    )(implicit metadataOpts: MetadataOptions, logger: Logger, config: ServiceConfig, project: Project): Option[Json] = {
      val id           = res.id.value
      val keysToRemove = if (includeMetadata) Seq.empty[String] else metaKeys

      val metaGraph   = if (includeMetadata) Graph(id) ++ res.metadata(metadataOpts) else Graph(id)
      val transformed =
        if (sourceAsText)
          metaGraph.append(nxv.original_source, res.value.source.removeKeys(metaKeys: _*).noSpaces).toJson(ctx)
        else
          metaGraph.toJson(ctx).map(metaJson => res.value.source.removeKeys(keysToRemove: _*) deepMerge metaJson)

      transformed match {
        case Left(err)    =>
          logger.error(
            s"Could not convert resource with id '${res.id}' and value '${res.value}' from Graph back to json. Reason: '$err'"
          )
          None
        case Right(value) => Some(value.removeNestedKeys("@context"))
      }
    }
  }

  object ElasticSearchView {
    val allField              = "_all_fields"
    private val defaultViewId = UUID.fromString("684bd815-9273-46f4-ac1c-0383d4a98254")

    /**
      * Default [[ElasticSearchView]] that gets created for every project.
      *
      * @param ref the project unique identifier
      */
    def default(ref: ProjectRef): ElasticSearchView = {
      val mapping = jsonContentOf("/elasticsearch/mapping.json")
      // format: off
      ElasticSearchView(mapping, Filter(), includeMetadata = true, sourceAsText = true, ref, nxv.defaultElasticSearchIndex.value, defaultViewId, 1L, deprecated = false)
      // format: on
    }

    final private val partialElasticSearchViewGraphDecoder: GraphDecoder[ResourceF[_] => ElasticSearchView] =
      GraphDecoder.instance { c =>
        for {
          uuid         <- c.down(nxv.uuid).as[UUID]
          mapping      <- c.down(nxv.mapping).as[Json]
          f            <- Filter.filterGraphDecoder(c)
          includeMeta  <- c.down(nxv.includeMetadata).withDefault(false)
          sourceAsText <- c.down(nxv.sourceAsText).withDefault(false)
        } yield (r: ResourceF[_]) =>
          ElasticSearchView(mapping, f, includeMeta, sourceAsText, r.id.parent, r.id.value, uuid, r.rev, r.deprecated)
      }

    final def apply(res: ResourceV): Either[Rejection, ElasticSearchView] =
      apply(res, res.value.graph.cursor)

    final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, ElasticSearchView] =
      partialElasticSearchViewGraphDecoder(cursor).map(_.apply(res)).leftRejectionFor(res.id.ref)

  }

  /**
    * Sparql specific view.
    *
    * @param filter          the filtering options for the view
    * @param includeMetadata flag to include or exclude metadata on the index
    * @param ref             a reference to the project that the view belongs to
    * @param id              the user facing view id
    * @param uuid            the underlying uuid generated for this view
    * @param rev             the view revision
    * @param deprecated      the deprecation state of the view
    */
  final case class SparqlView(
      filter: Filter,
      includeMetadata: Boolean,
      ref: ProjectRef,
      id: AbsoluteIri,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean
  ) extends SingleView {

    private def replace(query: String, id: AbsoluteIri, pagination: FromPagination): String =
      query
        .replaceAll(quote("{id}"), id.asString)
        .replaceAll(quote("{graph}"), (id + "graph").asString)
        .replaceAll(quote("{offset}"), pagination.from.toString)
        .replaceAll(quote("{size}"), pagination.size.toString)

    /**
      * Builds an Sparql INSERT query with all the triples of the passed resource
      *
      * @param res the resource
      * @return a DROP {...} INSERT DATA {triples} Sparql query
      */
    def buildInsertQuery(res: ResourceV): SparqlWriteQuery = {
      val graph = if (includeMetadata) res.value.graph else res.value.graph.removeMetadata
      SparqlWriteQuery.replace(res.id.toGraphUri, graph)
    }

    /**
      * Deletes the namedgraph where the triples for the resource are located inside the Sparql store.
      *
      * @param res the resource
      * @return a DROP {...} Sparql query
      */
    def buildDeleteQuery(res: ResourceV): SparqlWriteQuery =
      SparqlWriteQuery.drop(res.id.toGraphUri)

    /**
      * Runs incoming query using the provided SparqlView index against the provided [[BlazegraphClient]] endpoint
      *
      * @param id         the resource id. The query will select the incomings that match this id
      * @param pagination the pagination for the query
      * @tparam F the effect type
      */
    def incoming[F[_]](
        id: AbsoluteIri,
        pagination: FromPagination
    )(implicit
        client: BlazegraphClient[F],
        F: Functor[F],
        config: ServiceConfig
    ): F[LinkResults] =
      client.copy(namespace = index).queryRaw(replace(incomingQuery, id, pagination)).map(toSparqlLinks)

    /**
      * Runs outgoing query using the provided SparqlView index against the provided [[BlazegraphClient]] endpoint
      *
      * @param id                   the resource id. The query will select the incomings that match this id
      * @param pagination           the pagination for the query
      * @param includeExternalLinks flag to decide whether or not to include external links (not Nexus managed) in the query result
      * @tparam F the effect type
      */
    def outgoing[F[_]](id: AbsoluteIri, pagination: FromPagination, includeExternalLinks: Boolean)(implicit
        client: BlazegraphClient[F],
        F: Functor[F],
        config: ServiceConfig
    ): F[LinkResults] =
      if (includeExternalLinks)
        client.copy(namespace = index).queryRaw(replace(outgoingWithExternalQuery, id, pagination)).map(toSparqlLinks)
      else
        client.copy(namespace = index).queryRaw(replace(outgoingScopedQuery, id, pagination)).map(toSparqlLinks)

    private def toSparqlLinks(sparqlResults: SparqlResults): LinkResults = {
      val (count, results) =
        sparqlResults.results.bindings
          .foldLeft((0L, List.empty[SparqlLink])) {
            case ((total, acc), bindings) =>
              val newTotal = bindings.get("total").flatMap(v => Try(v.value.toLong).toOption).getOrElse(total)
              val res      = (SparqlResourceLink(bindings) orElse SparqlExternalLink(bindings)).map(_ :: acc).getOrElse(acc)
              (newTotal, res)
          }
      UnscoredQueryResults(count, results.map(UnscoredQueryResult(_)))
    }

    def index(implicit config: ServiceConfig): String = s"${config.kg.sparql.indexPrefix}_$name"

    def progressId(implicit config: ServiceConfig): String = s"sparql-indexer-$index"

    def createIndex[F[_]](implicit F: Effect[F], config: ServiceConfig, clients: Clients[F]): F[Unit] =
      clients.sparql.copy(namespace = index).createNamespace(properties) >> F.unit

    def deleteIndex[F[_]](implicit config: ServiceConfig, clients: Clients[F]): F[Boolean] =
      clients.sparql.copy(namespace = index).deleteNamespace

    def deleteResource[F[_]: Monad](resId: ResId)(implicit clients: Clients[F], config: ServiceConfig): F[Unit] = {
      val client = clients.sparql.copy(namespace = index).withRetryPolicy(config.kg.elasticSearch.indexing.retry)
      client.drop(resId.toGraphUri)
    }
  }

  object SparqlView {
    private val defaultViewId = UUID.fromString("d88b71d2-b8a4-4744-bf22-2d99ef5bd26b")

    /**
      * Default [[SparqlView]] that gets created for every project.
      *
      * @param ref the project unique identifier
      */
    def default(ref: ProjectRef): SparqlView      =
      // format: off
      SparqlView(Filter(), includeMetadata = true, ref, nxv.defaultSparqlIndex.value, defaultViewId, 1L, deprecated = false)
      // format: on

    private val properties: Map[String, String] = {
      val props = new Properties()
      props.load(getClass.getResourceAsStream("/blazegraph/index.properties"))
      props.asScala.toMap
    }
    private val incomingQuery: String             = contentOf("/blazegraph/incoming.txt")
    private val outgoingWithExternalQuery: String = contentOf("/blazegraph/outgoing_include_external.txt")
    private val outgoingScopedQuery: String       = contentOf("/blazegraph/outgoing_scoped.txt")

    final private val partialSparqlViewGraphDecoder: GraphDecoder[ResourceF[_] => SparqlView] =
      GraphDecoder.instance { c =>
        for {
          uuid        <- c.down(nxv.uuid).as[UUID]
          f           <- Filter.filterGraphDecoder(c)
          includeMeta <- c.down(nxv.includeMetadata).withDefault(false)
        } yield (r: ResourceF[_]) => SparqlView(f, includeMeta, r.id.parent, r.id.value, uuid, r.rev, r.deprecated)
      }

    final def apply(res: ResourceV): Either[Rejection, SparqlView] =
      apply(res, res.value.graph.cursor)

    final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, SparqlView] =
      partialSparqlViewGraphDecoder(cursor).map(_.apply(res)).leftRejectionFor(res.id.ref)
  }

  /**
    * Composite view. A source generates a temporary Sparql index which then is used to generate a set of indices
    *
    * @param sources          the source
    * @param projections     a set of indices created out of the temporary sparql index
    * @param rebuildStrategy the optional strategy to rebuild the projections
    * @param ref             a reference to the project that the view belongs to
    * @param id              the user facing view id
    * @param uuid            the underlying uuid generated for this view
    * @param rev             the view revision
    * @param deprecated      the deprecation state of the view
    */
  final case class CompositeView(
      sources: Set[Source],
      projections: Set[Projection],
      rebuildStrategy: Option[Interval],
      ref: ProjectRef,
      id: AbsoluteIri,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean
  ) extends IndexedView {

    override def progressId(implicit config: ServiceConfig): String =
      defaultSparqlView.progressId

    def defaultSparqlView: SparqlView =
      SparqlView(Filter(), includeMetadata = true, ref, nxv.defaultSparqlIndex.value, uuid, rev, deprecated)

    def sparqlView(source: Source): SparqlView =
      SparqlView(source.filter, includeMetadata = true, ref, nxv.defaultSparqlIndex.value, uuid, rev, deprecated)

    def progressId(sourceId: AbsoluteIri, projectionId: AbsoluteIri): String = s"${sourceId}_$projectionId"

    def projectionsProgress(projectionIdsOpt: Option[AbsoluteIri]): Set[String] =
      projectionIdsOpt match {
        case Some(pId) => sources.map(s => progressId(s.id, pId))
        case None      => for (s <- sources; p <- projections) yield progressId(s.id, p.view.id)
      }

    def projectionsBy[T <: Projection](implicit T: ClassTag[T]): Set[T] =
      projections.collect { case T(projection) => projection }

    def sourcesBy[T <: Source](implicit T: ClassTag[T]): Set[T]         =
      sources.collect { case T(source) => source }

    def source(sourceId: AbsoluteIri): Option[Source]                   =
      sources.find(_.id == sourceId)

    def projectSource(sourceId: AbsoluteIri): Option[ProjectIdentifier] =
      source(sourceId).map {
        case CrossProjectEventStream(_, _, projIdentifier, _) => projIdentifier
        case RemoteProjectEventStream(_, _, pLabel, _, _)     => pLabel
        case _                                                => ref
      }

    def projectsSource: Set[ProjectIdentifier] =
      sources.foldLeft(Set.empty[ProjectIdentifier]) {
        case (acc, s: CrossProjectEventStream)  => acc + s.project
        case (acc, s: RemoteProjectEventStream) => acc + s.project
        case (acc, _)                           => acc
      }

    def nextRestart(previous: Option[Instant]): Option[Instant] =
      (previous, rebuildStrategy).mapN { case (p, Interval(v)) => p.plusMillis(v.toMillis) }

  }

  object CompositeView {

    sealed trait Source extends Product with Serializable {
      def filter: Filter
      def id: AbsoluteIri
    }

    object Source {
      final case class ProjectEventStream(id: AbsoluteIri, filter: Filter) extends Source

      final case class CrossProjectEventStream(
          id: AbsoluteIri,
          filter: Filter,
          project: ProjectIdentifier,
          identities: Set[Identity]
      ) extends Source

      final case class RemoteProjectEventStream(
          id: AbsoluteIri,
          filter: Filter,
          project: ProjectLabel,
          endpoint: AbsoluteIri,
          token: Option[AccessToken]
      ) extends Source {

        private lazy val clientCfg = AdminClientConfig(endpoint, endpoint, "")

        // TODO: Remove when migrating ADMIN client
        implicit private val oldToken: Option[AuthToken] = token.map(t => AuthToken(t.value))

        def fetchProject[F[_]: Effect](implicit as: ActorSystem): F[Option[Project]] =
          AdminClient[F](clientCfg).fetchProject(project.organization, project.value)
      }
    }

    final case class Interval(value: FiniteDuration)

    sealed trait Projection extends Product with Serializable {
      def query: String
      def view: SingleView
      def indexResourceGraph[F[_]: Monad](res: ResourceV, graph: Graph)(implicit
          clients: Clients[F],
          config: ServiceConfig,
          metadataOpts: MetadataOptions,
          logger: Logger,
          project: Project
      ): F[Option[Unit]]

      /**
        * Runs a query replacing the {resource_id} with the resource id
        *
        * @param res the resource
        * @return a Sparql query results response
        */
      def runQuery[F[_]: Effect: Timer](res: ResourceV)(client: BlazegraphClient[F]): F[SparqlResults] =
        client.queryRaw(query.replaceAll(quote(idTemplating), s"<${res.id.value.asString}>"))
    }

    object Projection {
      final case class ElasticSearchProjection(query: String, view: ElasticSearchView, context: Json)
          extends Projection {

        /**
          * Attempts to convert the passed graph using the current context to an ElasticSearch Document to be indexed.
          * The resulting document will have different Json shape depending on the view configuration.
          *
          * @param res   the resource
          * @param graph the graph to be converted to a Document
          * @return Some(())) if the conversion was successful and the document was indexed, None otherwise
          */
        def indexResourceGraph[F[_]](
            res: ResourceV,
            graph: Graph
        )(implicit
            F: Monad[F],
            clients: Clients[F],
            config: ServiceConfig,
            metadataOpts: MetadataOptions,
            logger: Logger,
            project: Project
        ): F[Option[Unit]] = {
          val contextObj = Json.obj("@context" -> context)
          val finalCtx   = if (view.includeMetadata) view.ctx.appendContextOf(contextObj) else contextObj
          val client     = clients.elasticSearch.withRetryPolicy(config.kg.elasticSearch.indexing.retry)
          val finalGraph = if (view.includeMetadata) graph ++ res.metadata(metadataOpts) else graph
          finalGraph.withRoot(res.id.value).toJson(finalCtx) match {
            case Left(err)    =>
              val msg =
                s"Could not convert resource with id '${res.id}' and graph '${graph.ntriples}' from Graph back to json. Reason: '$err'"
              logger.error(msg)
              F.pure(None)
            case Right(value) =>
              client.create(view.index, res.id.value.asString, value.removeNestedKeys("@context")) >> F.pure(
                Some(())
              )
          }
        }
      }

      object ElasticSearchProjection {
        final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, ElasticSearchProjection] =
          ElasticSearchView(res, cursor).flatMap { view =>
            val extra = for {
              id      <- cursor.as[AbsoluteIri]
              query   <- cursor.down(nxv.query).as[String]
              context <- cursor.down(nxv.context).as[Json]
            } yield (id, query, context)

            extra.leftRejectionFor(res.id.ref).flatMap {
              case (id, query, context) =>
                validSparqlQuery(res, id, query).map(_ => ElasticSearchProjection(query, view.copy(id = id), context))
            }
          }
      }

      final case class SparqlProjection(query: String, view: SparqlView) extends Projection {

        /**
          * Attempts index the passed graph triples into Sparql store.
          *
          * @param res   the resource
          * @param graph the graph to be indexed
          * @return Some(())) if the triples were indexed, None otherwise
          */
        def indexResourceGraph[F[_]](
            res: ResourceV,
            graph: Graph
        )(implicit
            F: Monad[F],
            clients: Clients[F],
            config: ServiceConfig,
            metadataOpts: MetadataOptions,
            logger: Logger,
            project: Project
        ): F[Option[Unit]] = {
          val client = clients.sparql.copy(namespace = view.index).withRetryPolicy(config.kg.sparql.indexing.retry)
          client.replace(res.id.toGraphUri, graph) >> F.pure(Some(()))
        }
      }

      object SparqlProjection {
        final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, SparqlProjection]        =
          SparqlView(res, cursor).flatMap { view =>
            val extra = for {
              id    <- cursor.as[AbsoluteIri]
              query <- cursor.down(nxv.query).as[String]
            } yield (id, query)

            extra.leftRejectionFor(res.id.ref).flatMap {
              case (id, query) =>
                checkNotAllowedSparql(res, id).flatMap { _ =>
                  validSparqlQuery(res, id, query).map(_ => SparqlProjection(query, view.copy(id = id)))
                }
            }
          }
        private def checkNotAllowedSparql(res: ResourceF[_], id: AbsoluteIri): Either[Rejection, Unit] =
          if (id == nxv.defaultSparqlIndex.value)
            Left(InvalidResourceFormat(res.id.ref, s"'$id' cannot be '${nxv.defaultSparqlIndex}'."): Rejection)
          else
            Right(())
      }
    }

    private def validSparqlQuery(res: ResourceF[_], id: AbsoluteIri, q: String): Either[Rejection, Unit] =
      Try(QueryFactory.create(q.replaceAll(quote(idTemplating), s"<${res.id.value.asString}>"))) match {
        case Success(_) if !q.contains(idTemplating) =>
          val err = s"The provided SparQL does not target an id. The templating '$idTemplating' should be present."
          Left(InvalidResourceFormat(id.ref, err))
        case Success(query) if query.isConstructType =>
          Right(())
        case Success(_)                              =>
          Left(InvalidResourceFormat(id.ref, "The provided SparQL query is not a CONSTRUCT query"))
        case Failure(err)                            =>
          Left(InvalidResourceFormat(id.ref, s"The provided SparQL query is invalid: Reason: '${err.getMessage}'"))
      }

  }

  /**
    * Aggregation of [[ElasticSearchView]].
    *
    * @param value      the set of elastic search views that this view connects to when performing searches
    * @param ref        a reference to the project that the view belongs to
    * @param id         the user facing view id
    * @param uuid       the underlying uuid generated for this view
    * @param rev        the view revision
    * @param deprecated the deprecation state of the view
    */
  final case class AggregateElasticSearchView(
      value: Set[ViewRef],
      ref: ProjectRef,
      uuid: UUID,
      id: AbsoluteIri,
      rev: Long,
      deprecated: Boolean
  ) extends AggregateView

  object AggregateElasticSearchView {
    final private val partialGraphDecoder: GraphDecoder[ResourceF[_] => AggregateElasticSearchView]   =
      GraphDecoder.instance { c =>
        for {
          uuid     <- c.down(nxv.uuid).as[UUID]
          viewRefs <- c.downSet(nxv.views).as[Set[ViewRef]]
        } yield (res: ResourceF[_]) =>
          AggregateElasticSearchView(viewRefs, res.id.parent, uuid, res.id.value, res.rev, res.deprecated)
      }
    final def apply(res: ResourceV): Either[Rejection, AggregateElasticSearchView]                    =
      apply(res, res.value.graph.cursor)
    final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, AggregateElasticSearchView] =
      partialGraphDecoder(cursor).map(_.apply(res)).leftRejectionFor(res.id.ref)
  }

  /**
    * Aggregation of [[SparqlView]].
    *
    * @param value      the set of sparql views that this view connects to when performing searches
    * @param ref        a reference to the project that the view belongs to
    * @param id         the user facing view id
    * @param uuid       the underlying uuid generated for this view
    * @param rev        the view revision
    * @param deprecated the deprecation state of the view
    */
  final case class AggregateSparqlView(
      value: Set[ViewRef],
      ref: ProjectRef,
      uuid: UUID,
      id: AbsoluteIri,
      rev: Long,
      deprecated: Boolean
  ) extends AggregateView

  object AggregateSparqlView {
    final private val partialGraphDecoder: GraphDecoder[ResourceF[_] => AggregateSparqlView]   =
      GraphDecoder.instance { c =>
        for {
          uuid     <- c.down(nxv.uuid).as[UUID]
          viewRefs <- c.downSet(nxv.views).as[Set[ViewRef]]
        } yield (res: ResourceF[_]) =>
          AggregateSparqlView(viewRefs, res.id.parent, uuid, res.id.value, res.rev, res.deprecated)
      }
    final def apply(res: ResourceV): Either[Rejection, AggregateSparqlView]                    =
      apply(res, res.value.graph.cursor)
    final def apply(res: ResourceF[_], cursor: Cursor): Either[Rejection, AggregateSparqlView] =
      partialGraphDecoder(cursor).map(_.apply(res)).leftRejectionFor(res.id.ref)
  }

  /**
    * A view reference is a unique way to identify a view
    * @param project the project reference
    * @param id the view id
    */
  final case class ViewRef(project: ProjectIdentifier, id: AbsoluteIri)

  object ViewRef {
    implicit final val viewRefGraphDecoder: GraphDecoder[ViewRef] = GraphDecoder.instance { c =>
      for {
        project <- c.down(nxv.project).as[ProjectIdentifier]
        id      <- c.down(nxv.viewId).as[AbsoluteIri]
      } yield ViewRef(project, id)
    }
  }
}
