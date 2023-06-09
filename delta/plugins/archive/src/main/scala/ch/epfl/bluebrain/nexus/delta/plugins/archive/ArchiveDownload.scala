package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
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
    * Generates an akka [[Source]] of bytes representing the archive.
    *
    * @param value
    *   the archive value
    * @param project
    *   the archive parent project
    * @param format
    *   the requested archive format
    * @param ignoreNotFound
    *   do not fail when resource references are not found
    * @param caller
    *   the caller to be used for checking for access
    */
  def apply[F](
      value: ArchiveValue,
      project: ProjectRef,
      format: ArchiveFormat[F],
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[ArchiveRejection, AkkaSource]

}

object ArchiveDownload {

  implicit private val logger: Logger = Logger[ArchiveDownload]

  /**
    * The default [[ArchiveDownload]] implementation.
    *
    * @param aclCheck
    *   to check acls
    * @param fetchResource
    *   a function to fetch resources
    * @param fetchFileContent
    *   a function to fetch the file content
    */
  def apply(
      aclCheck: AclCheck,
      fetchResource: (ResourceRef, ProjectRef) => UIO[Option[JsonLdContent[_, _]]],
      fetchFileContent: (ResourceRef, ProjectRef, Caller) => IO[FileRejection, FileResponse]
  )(implicit sort: JsonKeyOrdering, baseUri: BaseUri, rcr: RemoteContextResolution): ArchiveDownload =
    new ArchiveDownload {

      implicit private val api: JsonLdApi = JsonLdJavaApi.lenient
      private val printer                 = Printer.spaces2.copy(dropNullValues = true)
      private val sourcePrinter           = Printer.spaces2.copy(dropNullValues = false)

      override def apply[F](
          value: ArchiveValue,
          project: ProjectRef,
          format: ArchiveFormat[F],
          ignoreNotFound: Boolean
      )(implicit caller: Caller): IO[ArchiveRejection, AkkaSource] = {
        implicit val entryOrdering: Ordering[F] = format.ordering
        val referenceList                       = value.resources.toList
        for {
          _       <- checkResourcePermissions(referenceList, project)
          entries <- referenceList.traverseFilter {
                       case ref: ResourceReference => resourceEntry(ref, project, format, ignoreNotFound)
                       case ref: FileReference     => fileEntry(ref, project, format, ignoreNotFound)
                     }
          sorted   = entries.sortBy { case (entry, _) => entry }
        } yield Source(sorted).via(format.writeFlow)
      }

      private def checkResourcePermissions(
          refs: List[ArchiveReference],
          project: ProjectRef
      )(implicit caller: Caller): IO[AuthorizationFailed, Unit] =
        aclCheck
          .mapFilterOrRaise(
            refs,
            (a: ArchiveReference) => AclAddress.Project(a.project.getOrElse(project)) -> resources.read,
            identity[ArchiveReference],
            address => IO.raiseError(AuthorizationFailed(address, resources.read))
          )
          .void

      private def fileEntry[Metadata](
          ref: FileReference,
          project: ProjectRef,
          format: ArchiveFormat[Metadata],
          ignoreNotFound: Boolean
      )(implicit
          caller: Caller
      ): IO[ArchiveRejection, Option[(Metadata, AkkaSource)]] = {
        val refProject = ref.project.getOrElse(project)
        // the required permissions are checked for each file content fetch
        val tarEntryIO = fetchFileContent(ref.ref, refProject, caller)
          .mapError {
            case _: FileRejection.FileNotFound                 => ResourceNotFound(ref.ref, project)
            case _: FileRejection.TagNotFound                  => ResourceNotFound(ref.ref, project)
            case _: FileRejection.RevisionNotFound             => ResourceNotFound(ref.ref, project)
            case FileRejection.AuthorizationFailed(addr, perm) => AuthorizationFailed(addr, perm)
            case other                                         => WrappedFileRejection(other)
          }
          .flatMap { fileResponse =>
            IO.fromEither(
              pathOf(ref, project, format, fileResponse.filename).map { path =>
                val metadata = format.metadata(path, fileResponse.bytes)
                Some((metadata, fileResponse.content))
              }
            )
          }
        if (ignoreNotFound) tarEntryIO.onErrorRecover { case _: ResourceNotFound => None }
        else tarEntryIO
      }

      private def pathOf(
          ref: FileReference,
          project: ProjectRef,
          format: ArchiveFormat[_],
          filename: String
      ): Either[FilenameTooLong, String] =
        ref.path.map { p => Right(p.value.toString) }.getOrElse {
          val p = ref.project.getOrElse(project)
          Either.cond(
            format != ArchiveFormat.Tar || filename.length < 100,
            s"$p/file/$filename",
            FilenameTooLong(ref.ref.original, p, filename)
          )
        }

      private def resourceEntry[Metadata](
          ref: ResourceReference,
          project: ProjectRef,
          format: ArchiveFormat[Metadata],
          ignoreNotFound: Boolean
      ): IO[ArchiveRejection, Option[(Metadata, AkkaSource)]] = {
        val tarEntryIO = resourceRefToByteString(ref, project).map { content =>
          val path     = pathOf(ref, project)
          val metadata = format.metadata(path, content.length.toLong)
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
        for {
          valueOpt <- fetchResource(ref.ref, p)
          value    <- IO.fromOption(valueOpt, ResourceNotFound(ref.ref, project))
          bytes    <- valueToByteString(value, ref.representationOrDefault).logAndDiscardErrors(
                        "serialize resource to ByteString"
                      )
        } yield bytes
      }

      private def valueToByteString[A](
          value: JsonLdContent[A, _],
          repr: ArchiveResourceRepresentation
      ): IO[RdfError, ByteString] = {
        implicit val encoder: JsonLdEncoder[A] = value.encoder
        repr match {
          case SourceJson      => UIO.pure(ByteString(prettyPrintSource(value.source)))
          case CompactedJsonLd => value.resource.toCompactedJsonLd.map(v => ByteString(prettyPrint(v.json)))
          case ExpandedJsonLd  => value.resource.toExpandedJsonLd.map(v => ByteString(prettyPrint(v.json)))
          case NTriples        => value.resource.toNTriples.map(v => ByteString(v.value))
          case NQuads          => value.resource.toNQuads.map(v => ByteString(v.value))
          case Dot             => value.resource.toDot.map(v => ByteString(v.value))
        }
      }

      private def prettyPrintSource(json: Json): ByteBuffer =
        sourcePrinter.printToByteBuffer(json.sort, StandardCharsets.UTF_8)

      private def prettyPrint(json: Json): ByteBuffer =
        printer.printToByteBuffer(json.sort, StandardCharsets.UTF_8)

      private def pathOf(ref: ResourceReference, project: ProjectRef): String =
        ref.path.map(_.value.toString).getOrElse {
          val p = ref.project.getOrElse(project)
          s"$p/${ref.representationOrDefault}/${ref.defaultFileName}"
        }
    }

}
