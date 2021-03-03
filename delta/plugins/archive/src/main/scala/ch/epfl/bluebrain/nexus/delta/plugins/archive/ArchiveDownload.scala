package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.alpakka.file.TarArchiveMetadata
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{AuthorizationFailed, ResourceNotFound, WrappedFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{ArchiveReference, ArchiveRejection, ArchiveResourceRepresentation, ArchiveValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, AkkaSource, ReferenceExchange}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * Archive download functionality.
  */
trait ArchiveDownload {

  /**
    * Generates an akka [[Source]] of bytes representing the tar.
    *
    * @param value          the archive value
    * @param project        the archive parent project
    * @param ignoreNotFound do not fail when resource references are not found
    * @param caller         the caller to be used for checking for access
    */
  def apply(
      value: ArchiveValue,
      project: ProjectRef,
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[ArchiveRejection, AkkaSource]

}

object ArchiveDownload {

  /**
    * The default [[ArchiveDownload]] implementation.
    *
    * @param exchanges the collection of [[ReferenceExchange]] implementations
    * @param acls      the acls module
    * @param files     the files module
    * @param sort      the configuration for sorting json keys
    */
  class ArchiveDownloadImpl(exchanges: Set[ReferenceExchange], acls: Acls, files: Files)(implicit sort: JsonKeyOrdering)
      extends ArchiveDownload {

    implicit private val logger: Logger = Logger[ArchiveDownload]

    override def apply(
        value: ArchiveValue,
        project: ProjectRef,
        ignoreNotFound: Boolean
    )(implicit caller: Caller): IO[ArchiveRejection, AkkaSource] = {
      val referenceList = value.resources.value.toList
      for {
        _          <- checkResourcePermissions(referenceList, project)
        optEntries <- referenceList.traverse {
                        case ref: ResourceReference => fetchResource(ref, project, ignoreNotFound)
                        case ref: FileReference     => fetchFile(ref, project, ignoreNotFound)
                      }
        entries     = optEntries.collect { case Some(value) => value } // discard None values
      } yield Source(entries).via(Archive.tar())
    }

    private def checkResourcePermissions(
        refs: List[ArchiveReference],
        project: ProjectRef
    )(implicit caller: Caller): IO[AuthorizationFailed, Unit] = {
      val authTargets = refs.collect { case ref: ResourceReference => ref }.map { ref =>
        val p       = ref.project.getOrElse(project)
        val address = AclAddress.Project(p)
        (address, resources.read)
      }
      acls.authorizeForAny(authTargets).flatMap { authResult =>
        authResult.find {
          case (_, false) => true // find the first entry that has a false auth result
          case _          => false
        } match {
          case Some((address, _)) => IO.raiseError(AuthorizationFailed(address, resources.read))
          case None               => IO.unit
        }
      }
    }

    private def fetchFile(ref: FileReference, project: ProjectRef, ignoreNotFound: Boolean)(implicit
        caller: Caller
    ): IO[ArchiveRejection, Option[(TarArchiveMetadata, AkkaSource)]] = {
      val refProject     = ref.project.getOrElse(project)
      // the required permissions are checked for each file content fetch
      val fileResponseIO = ref.ref match {
        case ResourceRef.Latest(iri)           => files.fetchContent(iri, refProject)
        case ResourceRef.Revision(_, iri, rev) => files.fetchContentAt(iri, refProject, rev)
        case ResourceRef.Tag(_, iri, tag)      => files.fetchContentBy(iri, refProject, tag)
      }
      val tarEntryIO     = fileResponseIO
        .map { fileResponse =>
          val path     = pathOf(ref, project, fileResponse.filename)
          val metadata = TarArchiveMetadata.create(path, fileResponse.bytes)
          Some((metadata, fileResponse.content))
        }
        .mapError {
          case _: FileRejection.FileNotFound                 => ResourceNotFound(ref.ref, project)
          case _: FileRejection.TagNotFound                  => ResourceNotFound(ref.ref, project)
          case _: FileRejection.RevisionNotFound             => ResourceNotFound(ref.ref, project)
          case FileRejection.AuthorizationFailed(addr, perm) => AuthorizationFailed(addr, perm)
          case other                                         => WrappedFileRejection(other)
        }
      if (ignoreNotFound) tarEntryIO.onErrorRecover { case _: ResourceNotFound => None }
      else tarEntryIO
    }

    private def fetchResource(
        ref: ResourceReference,
        project: ProjectRef,
        ignoreNotFound: Boolean
    ): IO[ArchiveRejection, Option[(TarArchiveMetadata, AkkaSource)]] = {
      val tarEntryIO = resourceRefToByteString(ref, project).map { content =>
        val path     = pathOf(ref, project)
        val metadata = TarArchiveMetadata.create(path, content.length.toLong)
        Some((metadata, Source.single(content)))
      }
      if (ignoreNotFound) tarEntryIO.onErrorHandle { _: ResourceNotFound => None }
      else tarEntryIO
    }

    private def resourceRefToByteString(
        ref: ResourceReference,
        project: ProjectRef
    ): IO[ResourceNotFound, ByteString] = {
      val p = ref.project.getOrElse(project)
      val r = ref.representation.getOrElse(CompactedJsonLd)
      UIO
        .tailRecM(exchanges.toList) { // try all reference exchanges one at a time until there's a result
          case Nil        => UIO.pure(Right(None))
          case ex :: rest => ex(p, ref.ref).map(_.toRight(rest).map(value => Option(value)))
        }
        .flatMap {
          case Some(value) => valueToByteString(value, r).logAndDiscardErrors("serialize resource to ByteString")
          case None        => IO.raiseError(ResourceNotFound(ref.ref, project))
        }
    }

    private def valueToByteString(
        value: ReferenceExchangeValue[_],
        repr: ArchiveResourceRepresentation
    ): IO[RdfError, ByteString] = {
      repr match {
        case SourceJson      => UIO.pure(value.toSource.sort.spaces2).map(v => ByteString(v))
        case CompactedJsonLd => value.toCompacted.map(_.json.sort.spaces2).map(v => ByteString(v))
        case ExpandedJsonLd  => value.toExpanded.map(_.json.spaces2).map(v => ByteString(v))
        case NTriples        => value.toNTriples.map(v => ByteString(v.value))
        case Dot             => value.toDot.map(v => ByteString(v.value))
      }
    }

    private def pathOf(ref: ResourceReference, project: ProjectRef): String = {
      val p = ref.project.getOrElse(project)
      s"$p/${UrlUtils.encode(ref.ref.original.toString)}.json"
    }

    private def pathOf(ref: FileReference, project: ProjectRef, filename: String): String = {
      val p = ref.project.getOrElse(project)
      s"$p/$filename"
    }
  }

}
