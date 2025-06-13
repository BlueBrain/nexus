package ai.senscience.nexus.delta.plugins.archive

import ai.senscience.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, FileSelfReference, ResourceReference}
import ai.senscience.nexus.delta.plugins.archive.model.ArchiveRejection.{InvalidFileSelf, ResourceNotFound, WrappedFileRejection}
import ai.senscience.nexus.delta.plugins.archive.model.{ArchiveReference, ArchiveValue, FullArchiveReference, Zip}
import akka.stream.alpakka.file.ArchiveMetadata
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileId, FileRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.OriginalSource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRepresentation}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, ResourceShifts}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, Printer}

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
    * @param ignoreNotFound
    *   do not fail when resource references are not found
    * @param caller
    *   the caller to be used for checking for access
    */
  def apply(
      value: ArchiveValue,
      project: ProjectRef,
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[AkkaSource]

}

object ArchiveDownload {

  private val logger = Logger[ArchiveDownload]

  case class ArchiveDownloadError(filename: String, response: Complete[JsonLdValue]) extends SDKError {
    override def getMessage: String = {
      s"Error streaming file '$filename' for archive: ${response.value.value}"
    }
  }

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
      fetchResource: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[?]]],
      fetchFileContent: (ResourceRef, ProjectRef, Caller) => IO[FileResponse],
      fileSelf: FileSelf
  )(implicit
      sort: JsonKeyOrdering,
      baseUri: BaseUri,
      rcr: RemoteContextResolution
  ): ArchiveDownload =
    new ArchiveDownload {

      implicit private val api: JsonLdApi = TitaniumJsonLdApi.lenient
      private val printer                 = Printer.spaces2.copy(dropNullValues = true)
      private val sourcePrinter           = Printer.spaces2.copy(dropNullValues = false)

      override def apply(
          value: ArchiveValue,
          project: ProjectRef,
          ignoreNotFound: Boolean
      )(implicit caller: Caller): IO[AkkaSource] = {
        for {
          references  <- value.resources.toList.traverse(toFullReference)
          _           <- checkResourcePermissions(references, project)
          contentList <- resolveReferences(references, project, ignoreNotFound)
        } yield {
          Source(contentList).via(Zip.writeFlow)
        }
      }

      private def toFullReference(archiveReference: ArchiveReference): IO[FullArchiveReference] = {
        archiveReference match {
          case reference: FullArchiveReference => IO.pure(reference)
          case reference: FileSelfReference    =>
            fileSelf
              .parse(reference.value)
              .map { case (projectRef, resourceRef) =>
                FileReference(resourceRef, Some(projectRef), reference.path)
              }
              .adaptError { case e: ParsingError =>
                InvalidFileSelf(e)
              }
        }
      }

      private def resolveReferences(
          references: List[FullArchiveReference],
          project: ProjectRef,
          ignoreNotFound: Boolean
      )(implicit caller: Caller) = {
        references
          .traverseFilter {
            case ref: FileReference     => fileEntry(ref, project, ignoreNotFound)
            case ref: ResourceReference => resourceEntry(ref, project, ignoreNotFound)
          }
          .map(sortWith)
          .map(asSourceList)
      }

      private def sortWith(list: List[(ArchiveMetadata, IO[AkkaSource])]): List[(ArchiveMetadata, IO[AkkaSource])] =
        list.sortBy { case (entry, _) => entry }(Zip.ordering)

      private def asSourceList(
          list: List[(ArchiveMetadata, IO[AkkaSource])]
      ) =
        list.map { case (metadata, source) =>
          metadata -> Source.lazyFutureSource(() => source.unsafeToFuture())
        }

      private def checkResourcePermissions(
          refs: List[FullArchiveReference],
          project: ProjectRef
      )(implicit caller: Caller): IO[Unit] =
        aclCheck
          .mapFilterOrRaise(
            refs,
            (a: FullArchiveReference) => AclAddress.Project(a.project.getOrElse(project)) -> resources.read,
            identity[ArchiveReference],
            address => IO.raiseError(AuthorizationFailed(address, resources.read))
          )
          .void

      private def fileEntry(
          ref: FileReference,
          project: ProjectRef,
          ignoreNotFound: Boolean
      )(implicit
          caller: Caller
      ): IO[Option[(ArchiveMetadata, IO[AkkaSource])]] = {
        val refProject = ref.project.getOrElse(project)
        // the required permissions are checked for each file content fetch
        val entry      = fetchFileContent(ref.ref, refProject, caller)
          .adaptError {
            case _: FileRejection.FileNotFound     => ResourceNotFound(ref.ref, project)
            case _: FileRejection.TagNotFound      => ResourceNotFound(ref.ref, project)
            case _: FileRejection.RevisionNotFound => ResourceNotFound(ref.ref, project)
            case other: FileRejection              => WrappedFileRejection(other)
          }
          .map { case FileResponse(fileMetadata, content) =>
            val path                        = pathOf(ref, project, fileMetadata.filename)
            val archiveMetadata             = Zip.metadata(path)
            val contentTask: IO[AkkaSource] = content.flatMap {
              case Left(response) =>
                logger.error(
                  s"Error streaming file '${fileMetadata.filename}' for archive: ${response.value.value}"
                ) >> IO.raiseError(ArchiveDownloadError(fileMetadata.filename, response))
              case Right(r)       => IO.pure(r)
            }
            Option((archiveMetadata, contentTask))
          }
        if (ignoreNotFound) entry.recover { case _: ResourceNotFound => None } else entry
      }

      private def pathOf(
          ref: FileReference,
          project: ProjectRef,
          filename: String
      ): String =
        ref.path.map(_.value.toString).getOrElse {
          val p = ref.project.getOrElse(project)
          s"$p/file/$filename"
        }

      private def resourceEntry(
          ref: ResourceReference,
          project: ProjectRef,
          ignoreNotFound: Boolean
      ): IO[Option[(ArchiveMetadata, IO[AkkaSource])]] = {
        val archiveEntry = resourceRefToByteString(ref, project).map { content =>
          val path     = pathOf(ref, project)
          val metadata = Zip.metadata(path)
          Option((metadata, IO.pure(Source.single(content))))
        }
        if (ignoreNotFound) archiveEntry.recover { case _: ResourceNotFound => None } else archiveEntry
      }

      private def resourceRefToByteString(
          ref: ResourceReference,
          project: ProjectRef
      ): IO[ByteString] = {
        val p = ref.project.getOrElse(project)
        for {
          valueOpt <- fetchResource(ref.ref, p)
          value    <- IO.fromOption(valueOpt)(ResourceNotFound(ref.ref, project))
          bytes    <- valueToByteString(value, ref.representationOrDefault).onError { case error =>
                        logger.error(error)(s"Serializing resource '$ref' to ByteString failed.")
                      }
        } yield bytes
      }

      private def valueToByteString[A](
          value: JsonLdContent[A],
          repr: ResourceRepresentation
      ): IO[ByteString] = {
        implicit val encoder: JsonLdEncoder[A] = value.encoder
        repr match {
          case SourceJson          => IO.pure(ByteString(prettyPrintSource(value.source)))
          case AnnotatedSourceJson =>
            val originalSource = OriginalSource.annotated(value.resource, value.source)
            IO.pure(ByteString(prettyPrintSource(originalSource.asJson)))
          case CompactedJsonLd     => value.resource.toCompactedJsonLd.map(v => ByteString(prettyPrint(v.json)))
          case ExpandedJsonLd      => value.resource.toExpandedJsonLd.map(v => ByteString(prettyPrint(v.json)))
          case NTriples            => value.resource.toNTriples.map(v => ByteString(v.value))
          case NQuads              => value.resource.toNQuads.map(v => ByteString(v.value))
          case Dot                 => value.resource.toDot.map(v => ByteString(v.value))
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

  def apply(aclCheck: AclCheck, shifts: ResourceShifts, files: Files, fileSelf: FileSelf)(implicit
      sort: JsonKeyOrdering,
      baseUri: BaseUri,
      rcr: RemoteContextResolution
  ): ArchiveDownload =
    ArchiveDownload(
      aclCheck,
      shifts.fetch,
      (id: ResourceRef, project: ProjectRef, caller: Caller) => files.fetchContent(FileId(id, project))(caller),
      fileSelf
    )

}
