package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant}

import akka.actor.ActorSystem
import cats.Monad
import cats.data.{EitherT, OptionT}
import cats.effect.{Effect, Timer}
import cats.syntax.functor._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resources
import ch.epfl.bluebrain.nexus.kg.resources.Command._
import ch.epfl.bluebrain.nexus.kg.resources.Event._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.Repo.Agg
import ch.epfl.bluebrain.nexus.kg.resources.State.{Current, Initial}
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.{AggregateConfig, AkkaAggregate}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import io.circe.Json
import retry.RetryPolicy

/**
  * Resource repository.
  *
  * @param agg          an aggregate instance for resources
  * @param clock        a clock used to record the instants when changes occur
  * @param toIdentifier a mapping from an id to a persistent id
  * @tparam F the repository effect type
  */
class Repo[F[_]: Monad](agg: Agg[F], clock: Clock, toIdentifier: ResId => String) {

  /**
    * Creates a new resource.
    *
    * @param id           the id of the resource
    * @param organization the organization id of the resource
    * @param schema       the schema that constrains the resource
    * @param types        the collection of known types of the resource
    * @param source       the source representation
    * @param subject      the subject that generated the change
    * @param instant      an optionally provided operation instant
    * @return either a rejection or the newly created resource in the F context
    */
  def create(
      id: ResId,
      organization: OrganizationRef,
      schema: Ref,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, Create(id, organization, schema, types, source, instant, subject))

  /**
    * Updates a resource.
    *
    * @param id      the id of the resource
    * @param schema  the schema that constrains the resource
    * @param rev     the last known revision of the resource
    * @param types   the new collection of known resource types
    * @param source  the source representation
    * @param subject the subject that generated the change
    * @param instant an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def update(
      id: ResId,
      schema: Ref,
      rev: Long,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, Update(id, schema, rev, types, source, instant, subject))

  /**
    * Deprecates a resource.
    *
    * @param id      the id of the resource
    * @param schema  the schema that constrains the resource
    * @param rev     the last known revision of the resource
    * @param subject the subject that generated the change
    * @param instant an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def deprecate(id: ResId, schema: Ref, rev: Long, instant: Instant = clock.instant)(implicit
      subject: Subject
  ): EitherT[F, Rejection, Resource] =
    evaluate(id, Deprecate(id, schema, rev, instant, subject))

  /**
    * Tags a resource. This operation aliases the provided ''targetRev'' with the  provided ''tag''.
    *
    * @param id        the id of the resource
    * @param schema    the schema that constrains the resource
    * @param rev       the last known revision of the resource
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''rev''
    * @param subject   the identity that generated the change
    * @param instant   an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def tag(id: ResId, schema: Ref, rev: Long, targetRev: Long, tag: String, instant: Instant = clock.instant)(implicit
      subject: Subject
  ): EitherT[F, Rejection, Resource] =
    evaluate(id, AddTag(id, schema, rev, targetRev, tag, instant, subject))

  /**
    * Creates a file resource.
    *
    * @param id           the id of the resource
    * @param organization the organization id of the resource
    * @param storage      the storage reference where the file was saved
    * @param fileAttr     the file attributes
    * @param instant      an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def createFile(
      id: ResId,
      organization: OrganizationRef,
      storage: StorageReference,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, CreateFile(id, organization, storage, fileAttr, instant, subject))

  private[resources] def createFileTest(
      id: ResId,
      organization: OrganizationRef,
      storage: StorageReference,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    test(id, CreateFile(id, organization, storage, fileAttr, instant, subject))

  /**
    * Updates the storage file attributes of a file resource.
    *
    * @param id         the id of the resource
    * @param storage    the storage reference where the file was saved
    * @param rev        the optional last known revision of the resource
    * @param attributes the storage file attributes
    * @param instant    an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def updateFileAttributes(
      id: ResId,
      storage: StorageReference,
      rev: Long,
      attributes: StorageFileAttributes,
      instant: Instant = clock.instant
  )(implicit
      subject: Subject
  ): EitherT[F, Rejection, Resource] =
    evaluate(id, UpdateFileAttributes(id, storage, rev, attributes, instant, subject))

  /**
    * Replaces a file resource.
    *
    * @param id       the id of the resource
    * @param storage  the storage reference where the file was saved
    * @param rev      the optional last known revision of the resource
    * @param fileAttr the file attributes
    * @param instant  an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def updateFile(
      id: ResId,
      storage: StorageReference,
      rev: Long,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, UpdateFile(id, storage, rev, fileAttr, instant, subject))

  private[resources] def updateFileTest(
      id: ResId,
      storage: StorageReference,
      rev: Long,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    test(id, UpdateFile(id, storage, rev, fileAttr, instant, subject))

  /**
    * Creates a link to an existing file.
    *
    * @param id           the id of the resource
    * @param organization the organization id of the resource
    * @param storage      the reference to the storage where the file was linked
    * @param fileAttr     the file attributes
    * @param instant      an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def createLink(
      id: ResId,
      organization: OrganizationRef,
      storage: StorageReference,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, CreateFile(id, organization, storage, fileAttr, instant, subject))

  private[resources] def createLinkTest(
      id: ResId,
      organization: OrganizationRef,
      storage: StorageReference,
      fileAttr: FileAttributes,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    test(id, CreateFile(id, organization, storage, fileAttr, instant, subject))

  /**
    * Updates a link to an existing file.
    *
    * @param id       the id of the resource
    * @param storage      the reference to the storage where the file was linked
    * @param fileAttr     the file attributes
    * @param rev      the last known resource revision
    * @param instant  an optionally provided operation instant
    * @return either a rejection or the new resource representation in the F context
    */
  def updateLink(
      id: ResId,
      storage: StorageReference,
      fileAttr: FileAttributes,
      rev: Long,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    evaluate(id, UpdateFile(id, storage, rev, fileAttr, instant, subject))

  private[resources] def updateLinkTest(
      id: ResId,
      storage: StorageReference,
      fileAttr: FileAttributes,
      rev: Long,
      instant: Instant = clock.instant
  )(implicit subject: Subject): EitherT[F, Rejection, Resource] =
    test(id, UpdateFile(id, storage, rev, fileAttr, instant, subject))

  /**
    * Attempts to read the resource identified by the argument id.
    *
    * @param id     the id of the resource
    * @param schema the optionally available schema of the resource
    * @return the optional resource in the F context
    */
  def get(id: ResId, schema: Option[Ref]): OptionT[F, Resource] =
    OptionT(agg.currentState(toIdentifier(id)).map {
      case state: Current if schema.forall(_ == state.schema) => state.asResource
      case _                                                  => None
    })

  /**
    * Attempts the read the resource identified by the argument id at the argument revision.
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @param schema the optionally available schema of the resource
    * @return the optional resource in the F context
    */
  def get(id: ResId, rev: Long, schema: Option[Ref]): OptionT[F, Resource] =
    OptionT(getState(id, rev).map {
      case state: Current if schema.forall(_ == state.schema) && rev == state.rev => state.asResource
      case _                                                                      => None
    })

  private def getState(id: ResId, rev: Long): F[State] =
    agg
      .foldLeft[State](toIdentifier(id), State.Initial) {
        case (state, event) if event.rev <= rev => Repo.next(state, event)
        case (state, _)                         => state
      }

  /**
    * Attempts to read the resource identified by the argument id at the revision identified by the argument tag. The
    * tag is transformed into a revision value using the latest resource tag to revision mapping.
    *
    * @param id  the id of the resource.
    * @param tag the tag of the resource
    * @return the optional resource in the F context
    */
  def get(id: ResId, tag: String, schema: Option[Ref]): OptionT[F, Resource] =
    for {
      resource <- get(id, schema)
      rev      <- OptionT.fromOption[F](resource.tags.get(tag))
      value    <- get(id, rev, schema)
    } yield value

  private def evaluate(id: ResId, cmd: Command): EitherT[F, Rejection, Resource] =
    for {
      result   <- EitherT(agg.evaluateS(toIdentifier(id), cmd))
      resource <- result.resourceT(UnexpectedState(id.ref))
    } yield resource

  private def test(id: ResId, cmd: Command): EitherT[F, Rejection, Resource] =
    for {
      result    <- EitherT(agg.test(toIdentifier(id), cmd))
      (state, _) = result
      resource  <- state.resourceT(UnexpectedState(id.ref))
    } yield resource
}

object Repo {

  /**
    * Aggregate type for resources.
    *
    * @tparam F the effect type under which the aggregate operates
    */
  type Agg[F[_]] = Aggregate[F, String, resources.Event, resources.State, resources.Command, resources.Rejection]

  final val initial: State = State.Initial

  implicit private def toDigest(digest: StorageDigest): Digest = Digest(digest.algorithm, digest.value)

  private def copyStorageAttr(attr: FileAttributes, storageFileAttr: StorageFileAttributes): FileAttributes =
    attr.copy(
      digest = storageFileAttr.digest,
      bytes = storageFileAttr.bytes,
      location = storageFileAttr.location,
      mediaType = storageFileAttr.mediaType
    )

  //noinspection NameBooleanParameters
  final def next(state: State, ev: Event): State =
    (state, ev) match {
      case (Initial, e @ Created(id, org, schema, types, value, tm, ident))                                                                      =>
        Current(id, org, e.rev, types, false, Map.empty, None, tm, tm, ident, ident, schema, value)
      case (Initial, e @ FileCreated(id, org, storage, file, tm, ident))                                                                         =>
        // format: off
        Current(id, org, e.rev, e.types, deprecated = false, Map.empty, Some(storage -> file), tm, tm, ident, ident, e.schema, Json.obj())
      // format: on
      case (Initial, _)                                                                                                                          => Initial
      case (c: Current, TagAdded(_, _, rev, targetRev, name, tm, ident))                                                                         =>
        c.copy(rev = rev, tags = c.tags + (name -> targetRev), updated = tm, updatedBy = ident)
      case (c: Current, _) if c.deprecated                                                                                                       => c
      case (c: Current, Deprecated(_, _, rev, _, tm, ident))                                                                                     =>
        c.copy(rev = rev, updated = tm, updatedBy = ident, deprecated = true)
      case (c: Current, Updated(_, _, rev, types, value, tm, ident))                                                                             =>
        c.copy(rev = rev, types = types, source = value, updated = tm, updatedBy = ident)
      // format: off
      case (c @ Current(_, _, _, _, _, _, Some((_, attr)), _, _, _, _, _, _), FileDigestUpdated(_, _, storage, rev, digest, tm, ident)) =>
        c.copy(rev = rev, file = Some(storage -> attr.copy(digest = digest)), updated = tm, updatedBy = ident)
      // format: on
      case (c: Current, _: FileDigestUpdated)                                                                                                    => c //that never happens
      // format: off
      case (c @ Current(_, _, _, _, _, _, Some((_, attr)), _, _, _, _, _, _), FileAttributesUpdated(_, _, storage, rev, storageAttr, tm, ident)) =>
        c.copy(rev = rev, file = Some(storage -> copyStorageAttr(attr, storageAttr)), updated = tm, updatedBy = ident)
      // format: on
      case (c: Current, _: FileAttributesUpdated)                                                                                                => c //that never happens
      case (c: Current, FileUpdated(_, _, storage, rev, file, tm, ident))                                                                        =>
        c.copy(rev = rev, file = Some(storage -> file), updated = tm, updatedBy = ident)
    }

  final def eval(state: State, cmd: Command): Either[Rejection, Event] = {
    def create(c: Create): Either[Rejection, Created] =
      state match {
        case _ if c.schema == fileRef => Left(NotAFileResource(c.id.ref))
        case Initial                  => Right(Created(c.id, c.organization, c.schema, c.types, c.source, c.instant, c.subject))
        case _                        => Left(ResourceAlreadyExists(c.id.ref))
      }

    def createFile(c: CreateFile): Either[Rejection, FileCreated] =
      state match {
        case Initial => Right(FileCreated(c.id, c.organization, c.storage, c.value, c.instant, c.subject))
        case _       => Left(ResourceAlreadyExists(c.id.ref))
      }

    def updateDigest(c: UpdateFileDigest): Either[Rejection, FileDigestUpdated] =
      state match {
        case Initial                      => Left(NotFound(c.id.ref))
        case s: Current if s.rev != c.rev => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.deprecated   => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current if s.file.isEmpty => Left(NotAFileResource(c.id.ref))
        case s: Current                   =>
          Right(FileDigestUpdated(s.id, s.organization, c.storage, s.rev + 1, c.value, c.instant, c.subject))
      }

    def updateAttributes(c: UpdateFileAttributes): Either[Rejection, FileAttributesUpdated] =
      state match {
        case Initial                      => Left(NotFound(c.id.ref))
        case s: Current if s.rev != c.rev => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.deprecated   => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current if s.file.isEmpty => Left(NotAFileResource(c.id.ref))
        case s: Current                   =>
          Right(FileAttributesUpdated(s.id, s.organization, c.storage, s.rev + 1, c.value, c.instant, c.subject))
      }

    def updateFile(c: UpdateFile): Either[Rejection, FileUpdated] =
      state match {
        case Initial                        => Left(NotFound(c.id.ref))
        case s: Current if s.rev != c.rev   => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.deprecated     => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current if s.file.isEmpty   => Left(NotAFileResource(c.id.ref))
        case s: Current if digestIsEmpty(s) => Left(FileDigestNotComputed(c.id.ref))
        case s: Current                     => Right(FileUpdated(s.id, s.organization, c.storage, s.rev + 1, c.value, c.instant, c.subject))
      }

    def digestIsEmpty(s: Current): Boolean            =
      s.file.exists { case (_, attr) => attr.digest == Digest.empty }

    def update(c: Update): Either[Rejection, Updated] =
      state match {
        case Initial                            => Left(NotFound(c.id.ref))
        case s: Current if s.schema != c.schema => Left(NotFound(c.id.ref, schemaOpt = Some(c.schema)))
        case s: Current if s.rev != c.rev       => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.deprecated         => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current                         => Right(Updated(s.id, s.organization, s.rev + 1, c.types, c.source, c.instant, c.subject))
      }

    def tag(c: AddTag): Either[Rejection, TagAdded] =
      state match {
        case Initial                            => Left(NotFound(c.id.ref))
        case s: Current if s.schema != c.schema => Left(NotFound(c.id.ref, schemaOpt = Some(c.schema)))
        case s: Current if s.rev != c.rev       => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.rev < c.targetRev  => Left(IncorrectRev(c.id.ref, c.targetRev, s.rev))
        case s: Current if s.deprecated         => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current                         => Right(TagAdded(s.id, s.organization, s.rev + 1, c.targetRev, c.tag, c.instant, c.subject))
      }

    def deprecate(c: Deprecate): Either[Rejection, Deprecated] =
      state match {
        case Initial                            => Left(NotFound(c.id.ref))
        case s: Current if s.schema != c.schema => Left(NotFound(c.id.ref, schemaOpt = Some(c.schema)))
        case s: Current if s.rev != c.rev       => Left(IncorrectRev(c.id.ref, c.rev, s.rev))
        case s: Current if s.deprecated         => Left(ResourceIsDeprecated(c.id.ref))
        case s: Current                         => Right(Deprecated(s.id, s.organization, s.rev + 1, s.types, c.instant, c.subject))
      }

    cmd match {
      case cmd: Create               => create(cmd)
      case cmd: CreateFile           => createFile(cmd)
      case cmd: UpdateFileDigest     => updateDigest(cmd)
      case cmd: UpdateFileAttributes => updateAttributes(cmd)
      case cmd: UpdateFile           => updateFile(cmd)
      case cmd: Update               => update(cmd)
      case cmd: Deprecate            => deprecate(cmd)
      case cmd: AddTag               => tag(cmd)
    }
  }

  private def aggregate[F[_]: Effect: Timer](implicit
      as: ActorSystem,
      aggCfg: AggregateConfig,
      F: Monad[F]
  ): F[Agg[F]] = {
    implicit val retryPolicy: RetryPolicy[F] = aggCfg.retry.retryPolicy[F]
    AkkaAggregate.sharded[F](
      "resources",
      initial,
      next,
      (st, cmd) => F.pure(eval(st, cmd)),
      aggCfg.passivationStrategy(),
      aggCfg.akkaAggregateConfig,
      aggCfg.shards
    )
  }

  final def apply[F[_]: Effect: Timer](implicit
      as: ActorSystem,
      aggCfg: AggregateConfig,
      clock: Clock = Clock.systemUTC
  ): F[Repo[F]] =
    aggregate[F].map(agg => new Repo[F](agg, clock, resId => s"${resId.parent.id}-${resId.value.show}"))

}
