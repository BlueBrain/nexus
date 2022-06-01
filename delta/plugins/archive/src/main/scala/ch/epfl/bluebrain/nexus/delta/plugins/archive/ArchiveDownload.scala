package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.alpakka.file.TarArchiveMetadata
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, AkkaSource, ReferenceExchange}
import com.typesafe.scalalogging.Logger
import io.circe.{Json, Printer}
import monix.bio.{IO, UIO}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
  * Archive download functionality.
  */
trait ArchiveDownload {

  /**
    * Generates an akka [[Source]] of bytes representing the tar.
    *
    * @param value
    *   the archive value
    * @param project
    *   the archive parent project
    * @param ignoreNotFound
    *   do not fail when resource references are not found
    * @param caller
    *   the caller to be used for checking for access
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
    * @param exchanges
    *   the collection of [[ReferenceExchange]] implementations
    * @param acls
    *   the acls module
    * @param files
    *   the files module
    * @param sort
    *   the configuration for sorting json keys
    */
  class ArchiveDownloadImpl(exchanges: List[ReferenceExchange], acls: Acls, files: Files)(implicit
      sort: JsonKeyOrdering,
      baseUri: BaseUri,
      rcr: RemoteContextResolution
  ) extends ArchiveDownload {

    implicit private val logger: Logger = Logger[ArchiveDownload]
    implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

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
        sorted      = entries.sortBy(_._1.filePath)
      } yield Source(sorted).via(Archive.tar())
    }

    private def checkResourcePermissions(
        refs: List[ArchiveReference],
        project: ProjectRef
    )(implicit caller: Caller): IO[AuthorizationFailed, Unit] = {
      val authTargets = refs
        .collect { case ref: ResourceReference => ref }
        .map { ref =>
          val p       = ref.project.getOrElse(project)
          val address = AclAddress.Project(p)
          (address, resources.read)
        }
        .toSet
      acls.authorizeForAny(authTargets).flatMap { authResult =>
        IO.fromEither(
          authResult
            .collectFirst { case (addr, false) => addr } // find the first entry that has a false auth result
            .map(AuthorizationFailed(_, resources.read))
            .toLeft(())
        )
      }
    }

    private def fetchFile(ref: FileReference, project: ProjectRef, ignoreNotFound: Boolean)(implicit
        caller: Caller
    ): IO[ArchiveRejection, Option[(TarArchiveMetadata, AkkaSource)]] = {
      val refProject = ref.project.getOrElse(project)
      // the required permissions are checked for each file content fetch
      val tarEntryIO = files
        .fetchContent(IdSegmentRef(ref.ref), refProject)
        .mapError {
          case _: FileRejection.FileNotFound                 => ResourceNotFound(ref.ref, project)
          case _: FileRejection.TagNotFound                  => ResourceNotFound(ref.ref, project)
          case _: FileRejection.RevisionNotFound             => ResourceNotFound(ref.ref, project)
          case FileRejection.AuthorizationFailed(addr, perm) => AuthorizationFailed(addr, perm)
          case other                                         => WrappedFileRejection(other)
        }
        .flatMap { fileResponse =>
          IO.fromEither(
            pathOf(ref, project, fileResponse.filename).map { path =>
              val metadata = TarArchiveMetadata.create(path, fileResponse.bytes)
              Some((metadata, fileResponse.content))
            }
          )
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
      UIO
        .tailRecM(exchanges) { // try all reference exchanges one at a time until there's a result
          case Nil              => UIO.pure(Right(None))
          case exchange :: rest => exchange.fetch(p, ref.ref).map(_.toRight(rest).map(Some.apply))
        }
        .flatMap {
          case Some(value) =>
            valueToByteString(value, ref.representationOrDefault).logAndDiscardErrors(
              "serialize resource to ByteString"
            )
          case None        => IO.raiseError(ResourceNotFound(ref.ref, project))
        }
    }

    private def valueToByteString[A](
        value: ReferenceExchangeValue[A],
        repr: ArchiveResourceRepresentation
    ): IO[RdfError, ByteString] = {
      implicit val encoder: JsonLdEncoder[A] = value.encoder
      repr match {
        case SourceJson      => UIO.pure(ByteString(prettyPrint(value.source)))
        case CompactedJsonLd => value.resource.toCompactedJsonLd.map(v => ByteString(prettyPrint(v.json)))
        case ExpandedJsonLd  => value.resource.toExpandedJsonLd.map(v => ByteString(prettyPrint(v.json)))
        case NTriples        => value.resource.toNTriples.map(v => ByteString(v.value))
        case NQuads          => value.resource.toNQuads.map(v => ByteString(v.value))
        case Dot             => value.resource.toDot.map(v => ByteString(v.value))
      }
    }

    private def pathOf(ref: ResourceReference, project: ProjectRef): String = ref.path.map(_.value.toString).getOrElse {
      val p = ref.project.getOrElse(project)
      s"$p/${ref.representationOrDefault}/${ref.defaultFileName}"
    }

    private def pathOf(ref: FileReference, project: ProjectRef, filename: String): Either[FilenameTooLong, String] =
      ref.path.map { p => Right(p.value.toString) }.getOrElse {
        val p = ref.project.getOrElse(project)
        Either.cond(
          filename.length < 100,
          s"$p/file/$filename",
          FilenameTooLong(ref.ref.original, p, filename)
        )
      }

    private val printer = Printer.spaces2.copy(dropNullValues = true)

    private def prettyPrint(json: Json): ByteBuffer =
      printer.printToByteBuffer(json.sort, StandardCharsets.UTF_8)
  }

}
