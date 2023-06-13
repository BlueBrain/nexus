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
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.{Json, Printer}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

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
  def apply[M](
      value: ArchiveValue,
      project: ProjectRef,
      format: ArchiveFormat[M],
      ignoreNotFound: Boolean
  )(implicit caller: Caller, scheduler: Scheduler): IO[ArchiveRejection, AkkaSource]

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

      override def apply[M](
          value: ArchiveValue,
          project: ProjectRef,
          format: ArchiveFormat[M],
          ignoreNotFound: Boolean
      )(implicit caller: Caller, scheduler: Scheduler): IO[ArchiveRejection, AkkaSource] = {
        val references = value.resources.toList
        for {
          _       <- checkResourcePermissions(references, project)
          contentStream <- resolveReferencesAsStream(references, project, ignoreNotFound, format)
        } yield  {
          Source.fromGraph(StreamConverter(contentStream)).via(format.writeFlow)
        }
      }

      private def resolveReferencesAsStream[M](references: List[ArchiveReference], project: ProjectRef, ignoreNotFound: Boolean, format: ArchiveFormat[M])(implicit caller: Caller): IO[ArchiveRejection, Stream[Task, (M, AkkaSource)]] = {
        references.traverseFilter {
          case ref: FileReference => fileEntry(ref, project, format, ignoreNotFound)
          case ref: ResourceReference => resourceEntry(ref, project, format, ignoreNotFound)
        }.map(sortWith(format))
          .map(asStream)
      }

      private def sortWith[M](format: ArchiveFormat[M])(list: List[(M, Task[AkkaSource])]): List[(M, Task[AkkaSource])] = {
        list.sortBy {
          case (entry, _) => entry
        }(format.ordering)
      }

      private def asStream[M](list: List[(M, Task[AkkaSource])]) = {
        fs2.Stream.iterable(list).evalMap[Task, (M, AkkaSource)] { case (metadata, source) =>
          source.map(metadata -> _)
        }
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
      ): IO[ArchiveRejection, Option[(Metadata, Task[AkkaSource])]] = {
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
          .flatMap { case FileResponse(fileMetadata, content) =>
            IO.fromEither(
              pathOf(ref, project, format, fileMetadata.filename).map { path =>
                val archiveMetadata = format.metadata(path, fileMetadata.bytes)
                val contentTask: Task[AkkaSource] = content.mapError(_ => new RuntimeException())
                Some((archiveMetadata, contentTask))
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
      ): IO[ArchiveRejection, Option[(Metadata, Task[AkkaSource])]] = {
        val archiveEntry = resourceRefToByteString(ref, project).map { content =>
          val path     = pathOf(ref, project)
          val metadata = format.metadata(path, content.length.toLong)
          Some((metadata, Task.pure(Source.single(content))))
        }
        if (ignoreNotFound) archiveEntry.onErrorHandle { _: ResourceNotFound => None }
        else archiveEntry
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
