package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Duration, Instant}

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveEncoder._
import ch.epfl.bluebrain.nexus.kg.archives.{Archive, ArchiveSource, FetchResource}
import ch.epfl.bluebrain.nexus.kg.cache.Caches
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.ArchivesConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import io.circe.Json

class Archives[F[_]](resources: Resources[F], files: Files[F], cache: Caches[F])(implicit
    as: ActorSystem,
    materializer: Materializer[F],
    config: AppConfig,
    clock: Clock,
    F: Effect[F]
) {
  implicit private val pc: ProjectCache[F] = cache.project

  /**
    * Creates an archive.
    *
    * @param source the source representation in JSON-LD
    * @return either a rejection or the resource representation in the F context
    */
  def create(source: Json)(implicit project: ProjectResource, subject: Subject): RejOrResource[F] =
    materializer(source.addContext(archiveCtxUri)).flatMap {
      case (id, Value(_, _, graph)) => create(Id(ProjectRef(project.uuid), id), graph, source)
    }

  /**
    * Creates an archive.
    *
    * @param id     the id of the resource
    * @param source the source representation in JSON-LD
    * @return either a rejection or the resource representation in the F context
    */
  def create(id: ResId, source: Json)(implicit project: ProjectResource, subject: Subject): RejOrResource[F] =
    materializer(source.addContext(archiveCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, graph, source)
    }

  private def create(id: ResId, graph: Graph, source: Json)(implicit
      project: ProjectResource,
      subject: Subject
  ): RejOrResource[F] = {
    implicit val archivesCfg: ArchivesConfig = config.archives
    val typedGraph                           = graph.append(rdf.tpe, nxv.Archive)
    val types                                = typedGraph.rootTypes
    for {
      _       <- validateShacl(typedGraph)
      archive <- Archive(id.value, typedGraph)
      _       <- cache.archive.put(archive).toRight(ResourceAlreadyExists(id.ref): Rejection)
    } yield
    // format: off
      ResourceF(id, 1L, types, false, Map.empty, None, archive.created, archive.created, archive.createdBy, archive.createdBy, archiveRef, source)
    // format: on
  }

  private def validateShacl(data: Graph): EitherT[F, Rejection, Unit] =
    toEitherT(archiveRef, ShaclEngine(data.asJena, archiveSchemaModel, validateShapes = false, reportDetails = true))

  private def fetchArchive(id: ResId): RejOrArchive[F] =
    cache.archive.get(id).toRight(notFound(id.ref, schema = Some(archiveRef)))

  /**
    * Fetches the archive.
    *
    * @param id the id of the collection source
    * @return either a rejection or the bytestring source in the F context
    */
  def fetchArchive(
      id: ResId,
      ignoreNotFound: Boolean
  )(implicit acls: AccessControlLists, caller: Caller): RejOrAkkaSource[F] =
    fetchArchive(id: ResId).flatMap { archive =>
      implicit val fc: FetchResource[F, ArchiveSource] =
        FetchResource.akkaSource[F](resources, files, acls, caller, as, F, clock)
      if (ignoreNotFound)
        EitherT.right(archive.toTarIgnoreNotFound[F])
      else
        archive.toTar[F].toRight(ArchiveElementNotFound: Rejection)
    }

  /**
    * Fetches the archive resource.
    *
    * @param id the id of the collection source
    * @return either a rejection or the resourceV in the F context
    */
  def fetch(id: ResId)(implicit project: ProjectResource): RejOrResourceV[F] =
    fetchArchive(id).map {
      case a @ Archive(resId, created, createdBy, _) =>
        val source   = Json.obj().addContext(archiveCtxUri).addContext(resourceCtxUri)
        val ctx      = archiveCtx.contextValue deepMerge resourceCtx.contextValue
        val value    = Value(source, ctx, a.asGraph)
        val resource = ResourceF(
          resId,
          1L,
          Set(nxv.Archive.value),
          false,
          Map.empty,
          None,
          created,
          created,
          createdBy,
          createdBy,
          archiveRef,
          value
        )
        val graph    = Graph(value.graph.root, value.graph.triples ++ resource.metadata() + expireMetadata(id, created))
        resource.copy(value = value.copy(graph = graph))
    }

  private def expireMetadata(id: ResId, created: Instant): Triple = {
    val delta   = Duration.between(created, clock.instant())
    val expires = Math.max(config.archives.cacheInvalidateAfter.toSeconds - delta.getSeconds, 0)
    (id.value, nxv.expiresInSeconds, expires): Triple
  }
}

object Archives {
  final def apply[F[_]: Effect: Materializer](
      resources: Resources[F],
      files: Files[F],
      cache: Caches[F]
  )(implicit system: ActorSystem, config: AppConfig, clock: Clock): Archives[F] =
    new Archives(resources, files, cache)
}
