package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Duration, Instant}

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveEncoder._
import ch.epfl.bluebrain.nexus.kg.archives.{Archive, ArchiveCache}
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.KgConfig
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json

class Archives[F[_]](implicit
    cache: ArchiveCache[F],
    resources: Resources[F],
    files: Files[F],
    system: ActorSystem,
    materializer: Materializer[F],
    config: ServiceConfig,
    projectCache: ProjectCache[F],
    clock: Clock,
    F: Effect[F]
) {

  /**
    * Creates an archive.
    *
    * @param source the source representation in JSON-LD
    * @return either a rejection or the resource representation in the F context
    */
  def create(source: Json)(implicit project: Project, subject: Subject): RejOrResource[F] =
    materializer(source.addContext(archiveCtxUri)).flatMap {
      case (id, Value(_, _, graph)) => create(Id(project.ref, id), graph, source)
    }

  /**
    * Creates an archive.
    *
    * @param id     the id of the resource
    * @param source the source representation in JSON-LD
    * @return either a rejection or the resource representation in the F context
    */
  def create(id: ResId, source: Json)(implicit project: Project, subject: Subject): RejOrResource[F] =
    materializer(source.addContext(archiveCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, graph, source)
    }

  private def create(id: ResId, graph: Graph, source: Json)(implicit
      project: Project,
      subject: Subject
  ): RejOrResource[F] = {
    implicit val archivesCfg: KgConfig.ArchivesConfig = config.kg.archives
    val typedGraph                                    = graph.append(rdf.tpe, nxv.Archive)
    val types                                         = typedGraph.rootTypes
    for {
      _       <- validateShacl(typedGraph)
      archive <- Archive(id.value, typedGraph)
      _       <- cache.put(archive).toRight(ResourceAlreadyExists(id.ref): Rejection)
    } yield
    // format: off
      ResourceF(id, 1L, types, false, Map.empty, None, archive.created, archive.created, archive.createdBy, archive.createdBy, archiveRef, source)
    // format: on
  }

  private def validateShacl(data: Graph): EitherT[F, Rejection, Unit] =
    toEitherT(archiveRef, ShaclEngine(data.asJena, archiveSchemaModel, validateShapes = false, reportDetails = true))

  private def fetchArchive(id: ResId): RejOrArchive[F] =
    cache.get(id).toRight(notFound(id.ref, schema = Some(archiveRef)))

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
      if (ignoreNotFound) EitherT.right(archive.toTarIgnoreNotFound[F])
      else archive.toTar[F].toRight(ArchiveElementNotFound: Rejection)
    }

  /**
    * Fetches the archive resource.
    *
    * @param id the id of the collection source
    * @return either a rejection or the resourceV in the F context
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
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
    val expires = Math.max(config.kg.archives.cacheInvalidateAfter.toSeconds - delta.getSeconds, 0)
    (id.value, nxv.expiresInSeconds, expires): Triple
  }
}

object Archives {
  final def apply[F[_]: Effect: ArchiveCache: ProjectCache: Materializer](
      resources: Resources[F],
      files: Files[F]
  )(implicit system: ActorSystem, config: ServiceConfig, clock: Clock): Archives[F] = {
    implicit val r = resources
    implicit val f = files
    new Archives
  }
}
