package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.InternalError
import ch.epfl.bluebrain.nexus.kg.cache.StorageCache
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.Resources.generateId
import ch.epfl.bluebrain.nexus.kg.resources.file.File
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.{Fetch, FetchAttributes, Link, Save}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import io.circe.Json

class Files[F[_]](repo: Repo[F], storageCache: StorageCache[F])(implicit config: AppConfig, F: Effect[F]) {

  /**
    * Creates a file resource.
    *
    * @param storage    the storage where the file is going to be saved
    * @param fileDesc   the file description metadata
    * @param source     the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def create[In](
      storage: Storage,
      fileDesc: FileDescription,
      source: In
  )(implicit subject: Subject, project: ProjectResource, saveStorage: Save[F, In]): RejOrResource[F] =
    create(Id(ProjectRef(project.uuid), generateId(project.value.base)), storage, fileDesc, source)

  /**
    * Creates a file resource.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param fileDesc the file description metadata
    * @param source   the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def create[In](id: ResId, storage: Storage, fileDesc: FileDescription, source: In)(implicit
      subject: Subject,
      project: ProjectResource,
      saveStorage: Save[F, In]
  ): RejOrResource[F] = {
    val orgRef = OrganizationRef(project.value.organizationUuid)
    for {
      _       <- repo.createFileTest(id, orgRef, storage.reference, fileDesc.process(StoredSummary.empty))
      attr    <- EitherT.right(storage.save.apply(id, fileDesc, source))
      created <- repo.createFile(id, orgRef, storage.reference, attr)
    } yield created
  }

  /**
    * Updates the digest of a file resource if it is not already present.
    * The digest is fetched from the storage implementation and saved in the primary store
    *
    * @param id      the id of the resource
    * @return either a rejection or the new resource representation in the F context
    */
  def updateFileAttrEmpty(
      id: ResId
  )(implicit subject: Subject, fetchAttributes: FetchAttributes[F]): RejOrResource[F] = {
    def storageServerErrToKgError: PartialFunction[Throwable, F[StorageFileAttributes]] = {
      case err: StorageClientError.UnknownError =>
        F.raiseError(InternalError(s"Storage error for resource '${id.ref.show}'. Error: '${err.entityAsString}'"))
      case err                                  =>
        F.raiseError(err)
    }

    // format: off
    for {
      curr              <- repo.get(id, Some(fileRef)).toRight(notFound(id.ref, schema = Some(fileRef)))
      currFile          <- EitherT.fromEither[F](curr.file.toRight(notFound(id.ref, schema = Some(fileRef))))
      (storageRef, attr) = currFile
      storage           <- EitherT.fromOptionF(storageCache.get(id.parent, storageRef.id), UnexpectedState(storageRef.id.ref))
      fileAttr          <- if (attr.digest == Digest.empty) EitherT.right(storage.fetchAttributes.apply(attr.path).recoverWith(storageServerErrToKgError))
                           else EitherT.leftT[F, StorageFileAttributes](FileDigestAlreadyExists(id.ref): Rejection)
      _                 <- if (fileAttr.digest == StorageDigest.empty) EitherT.leftT[F, Resource](FileDigestNotComputed(id.ref))
                           else EitherT.rightT[F, Rejection](())
      updated           <- repo.updateFileAttributes(id, storage.reference, curr.rev, fileAttr)
    } yield updated
    // format: on
  }

  /**
    * Updates the file attributes of a file resource.
    *
    * @param id      the id of the resource
    * @param storage the storage reference
    * @param rev     the last known revision of the resource
    * @param attr  the attributes of the file in a json format
    * @return either a rejection or the new resource representation in the F context
    */
  def updateFileAttr(id: ResId, storage: Storage, rev: Long, attr: Json)(implicit subject: Subject): RejOrResource[F] =
    EitherT.fromEither[F](FileAttributes(id, attr)).flatMap(repo.updateFileAttributes(id, storage.reference, rev, _))

  /**
    * Replaces a file resource.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param rev      the last known revision of the resource
    * @param fileDesc the file description metadata
    * @param source   the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def update[In](id: ResId, storage: Storage, rev: Long, fileDesc: FileDescription, source: In)(implicit
      subject: Subject,
      saveStorage: Save[F, In]
  ): RejOrResource[F] =
    for {
      _       <- repo.updateFileTest(id, storage.reference, rev, fileDesc.process(StoredSummary.empty))
      attr    <- EitherT.right(storage.save.apply(id, fileDesc, source))
      created <- repo.updateFile(id, storage.reference, rev, attr)
    } yield created

  /**
    * Creates a link to an existing file.
    *
    * @param storage    the storage where the file is going to be saved
    * @param source     the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def createLink(
      storage: Storage,
      source: Json
  )(implicit subject: Subject, project: ProjectResource, linkStorage: Link[F]): RejOrResource[F] =
    createLink(Id(ProjectRef(project.uuid), generateId(project.value.base)), storage, source)

  /**
    * Creates a link to an existing file.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param source   the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def createLink(
      id: ResId,
      storage: Storage,
      source: Json
  )(implicit subject: Subject, project: ProjectResource, linkStorage: Link[F]): RejOrResource[F] = {
    val orgRef = OrganizationRef(project.value.organizationUuid)
    // format: off
    for {
      link      <- EitherT.fromEither[F](LinkDescription(id, source))
      fileDesc   = FileDescription.from(link)
      _         <- repo.createLinkTest(id, orgRef, storage.reference, fileDesc.process(StoredSummary.empty))
      attr      <- EitherT.right(storage.link.apply(id, fileDesc, link.path))
      created   <- repo.createLink(id, orgRef, storage.reference, attr)
    } yield created
    // format: on
  }

  /**
    * Updates a link to an existing file.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param rev      the last known resource revision
    * @param source   the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def updateLink(id: ResId, storage: Storage, rev: Long, source: Json)(implicit
      subject: Subject,
      linkStorage: Link[F]
  ): RejOrResource[F] =
    // format: off
    for {
      link      <- EitherT.fromEither[F](LinkDescription(id, source))
      fileDesc   = FileDescription.from(link)
      _         <- repo.updateLinkTest(id, storage.reference, fileDesc.process(StoredSummary.empty), rev)
      attr      <- EitherT.right(storage.link.apply(id, fileDesc, link.path))
      created   <- repo.updateLink(id, storage.reference, attr, rev)
    } yield created
  // format: on

  /**
    * Deprecates an existing file.
    *
    * @param id  the id of the file
    * @param rev the last known revision of the file
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.deprecate(id, fileRef, rev)

  /**
    * Attempts to stream the file resource for the latest revision.
    *
    * @param id     the id of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, Some(fileRef)).toRight(notFound(id.ref, schema = Some(fileRef))))

  /**
    * Attempts to stream the file resource with specific revision.
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId, rev: Long)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, rev, Some(fileRef)).toRight(notFound(id.ref, Some(rev), schema = Some(fileRef))))

  /**
    * Attempts to stream the file resource with specific tag. The
    * tag is transformed into a revision value using the latest resource tag to revision mapping.
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId, tag: String)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, tag, Some(fileRef)).toRight(notFound(id.ref, tag = Some(tag), schema = Some(fileRef))))

  private def fetch[Out](rejOrResource: RejOrResource[F])(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] = {
    def fileOrRejection(resource: Resource): Either[Rejection, (ProjectRef, StorageReference, File.FileAttributes)] =
      resource.file
        .map { case (ref, attr) => (resource.id.parent, ref, attr) }
        .toRight(notFound(resource.id.ref, schema = Some(fileRef)))

    def storageOrRejection(project: ProjectRef, ref: StorageReference, attr: FileAttributes) =
      storageCache.get(project, ref.id).map(_.map(_ -> attr).toRight(notFound(ref.id.ref)))

    rejOrResource
      .subflatMap(fileOrRejection)
      .flatMapF { case (project, ref, attr) => storageOrRejection(project, ref, attr) }
      .flatMapF {
        case (storage, fileAttr) => storage.fetch.apply(fileAttr).map(out => Right((storage, fileAttr, out)))
      }
  }

  /**
    * Lists files on the given project
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
    listResources(view, params.copy(schema = Some(fileSchemaUri)), pagination)

  /**
    * Lists incoming resources for the provided file ''id''
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
    * Lists outgoing resources for the provided file ''id''
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

}

object Files {

  final def apply[F[_]: Effect](repo: Repo[F], index: StorageCache[F])(implicit config: AppConfig): Files[F] =
    new Files[F](repo, index)
}
