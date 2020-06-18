package ch.epfl.bluebrain.nexus.kg.resources.file

import java.security.MessageDigest
import java.util.UUID

import cats.implicits._
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.{Empty, Segment, SingleSlash}
import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.iam.client.types.Permission
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{InvalidJsonLD, InvalidResourceFormat}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Rejection, ResId}
import ch.epfl.bluebrain.nexus.rdf.{Cursor, DecodingError, GraphDecoder, NonEmptyString}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}

import scala.annotation.tailrec
import scala.util.Try

object File {

  implicit final private val contentyTypeGraphDecoder: GraphDecoder[ContentType] =
    GraphDecoder.graphDecodeString.emap { str =>
      ContentType.parse(str).leftMap(_ => s"Unable to parse string '$str' as a valid Content-Type.")
    }
  implicit final private val pathGraphDecoder: GraphDecoder[Path]                =
    GraphDecoder.graphDecodeString.emap { str =>
      Try(Path(str)).toEither.leftMap(_ => s"Unable to parse string '$str' as a valid Path.")
    }
  implicit final private val uriGraphDecoder: GraphDecoder[Uri]                  =
    GraphDecoder.graphDecodeString.emap { str =>
      Try(Uri(str)).toEither.leftMap(_ => s"Unable to parse string '$str' as a valid Uri.")
    }

  val write: Permission = Permission.unsafe("files/write")

  /**
    * Holds the metadata information related to a file link.
    *
    * @param path      the target file relative location (from the storage)
    * @param filename  an optional filename
    * @param mediaType an optional media type
    */
  final case class LinkDescription(path: Path, filename: Option[String], mediaType: Option[ContentType])

  object LinkDescription {

    /**
      * Attempts to transform a JSON payload into a [[LinkDescription]].
      *
      * @param id     the resource identifier
      * @param source the JSON payload
      * @return a link description if the resource is compatible or a rejection otherwise
      */
    final def apply(id: ResId, source: Json): Either[Rejection, LinkDescription] = {
      // format: off
      for {
        graph       <- source.replaceContext(storageCtx).id(id.value).toGraph(id.value).left.map(_ => InvalidJsonLD("Invalid JSON payload."))
        c            = graph.cursor
        filename    <- c.down(nxv.filename).as[Option[NonEmptyString]].map(_.map(_.asString)).onError(id.ref, nxv.filename.prefix)
        mediaType   <- c.down(nxv.mediaType).as[Option[ContentType]].onError(id.ref, nxv.mediaType.prefix)
        path        <- c.down(nxv.path).as[Path].onError(id.ref, nxv.path.prefix)
      } yield LinkDescription(path, filename, mediaType)
      // format: on
    }

  }

  /**
    * Holds some of the metadata information related to a file.
    *
    * @param uuid      the unique id that identifies this file.
    * @param filename  the original filename of the file
    * @param mediaType the media type of the file
    */
  final case class FileDescription(uuid: UUID, filename: String, mediaType: Option[ContentType]) {
    lazy val defaultMediaType: ContentType             = mediaType.getOrElse(`application/octet-stream`)
    def process(stored: StoredSummary): FileAttributes =
      FileAttributes(uuid, stored.location, stored.path, filename, defaultMediaType, stored.bytes, stored.digest)
  }

  object FileDescription {
    def apply(filename: String, mediaType: ContentType): FileDescription =
      FileDescription(UUID.randomUUID, filename, Some(mediaType))

    def from(link: LinkDescription): FileDescription =
      FileDescription(UUID.randomUUID, link.filename.getOrElse(getFilename(link.path)), link.mediaType)

    @tailrec
    private def getFilename(path: Path): String =
      path match {
        case Empty | SingleSlash        => "unknown"
        case Segment(head, Empty)       => head
        case Segment(head, SingleSlash) => head
        case _                          => getFilename(path.tail)
      }
  }

  /**
    * Holds all the metadata information related to the file.
    *
    * @param uuid      the unique id that identifies this file.
    * @param location  the absolute location where the file gets stored
    * @param path      the relative path (from the storage) where the file gets stored
    * @param filename  the original filename of the file
    * @param mediaType the media type of the file
    * @param bytes     the size of the file file in bytes
    * @param digest    the digest information of the file
    */
  final case class FileAttributes(
      uuid: UUID,
      location: Uri,
      path: Path,
      filename: String,
      mediaType: ContentType,
      bytes: Long,
      digest: Digest
  )
  object FileAttributes {

    def apply(
        location: Uri,
        path: Path,
        filename: String,
        mediaType: ContentType,
        size: Long,
        digest: Digest
    ): FileAttributes =
      FileAttributes(UUID.randomUUID, location, path, filename, mediaType, size, digest)

    /**
      * Attempts to constructs a [[FileAttributes]] from the provided json
      *
      * @param resId the resource identifier
      * @param json  the payload
      * @return Right(attr) when successful and Left(rejection) when failed
      */
    final def apply(resId: ResId, json: Json): Either[Rejection, StorageFileAttributes] =
      // format: off
      for {
        graph     <- (json deepMerge fileAttrCtx).id(resId.value).toGraph(resId.value).leftMap(_ => InvalidResourceFormat(resId.ref, "Empty or wrong Json-LD"): Rejection)
        cursor     = graph.cursor
        _         <- cursor.expectType(nxv.UpdateFileAttributes.value).onError(resId.ref, "@type")
        mediaType <- cursor.down(nxv.mediaType).as[ContentType].onError(resId.ref, "mediaType")
        bytes     <- cursor.down(nxv.bytes).as[Long].onError(resId.ref, nxv.bytes.prefix)
        location  <- cursor.down(nxv.location).as[Uri].onError(resId.ref, nxv.location.prefix)
        digestC    = cursor.down(nxv.digest)
        algorithm <- decodeAlgorithm(digestC).onError(resId.ref, "algorithm")
        value     <- digestC.down(nxv.value).as[NonEmptyString].map(_.asString).onError(resId.ref, "value")
      } yield StorageFileAttributes(location, bytes, StorageDigest(algorithm, value), mediaType)
    // format: on

    private def decodeAlgorithm(c: Cursor): Either[DecodingError, String] = {
      val focus = c.down(nxv.algorithm)
      focus.as[String].flatMap { s =>
        Try(MessageDigest.getInstance(s))
          .map(_ => s)
          .toEither
          .leftMap(_ => DecodingError(s"Unable to decode string '$s' as a valid digest algorithm.", c.history))
      }
    }
  }

  /**
    * Digest related information of the file
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)
  object Digest {
    val empty: Digest = Digest("", "")
  }

  /**
    * The summary after the file has been stored
    *
    * @param location the absolute location where the file has been stored
    * @param path     the relative path (from the storage) where the file gets stored
    * @param bytes    the size of the file in bytes
    * @param digest   the digest related information of the file
    */
  final case class StoredSummary(location: Uri, path: Path, bytes: Long, digest: Digest)

  object StoredSummary {
    val empty: StoredSummary = StoredSummary(Uri.Empty, Path.Empty, 0L, Digest.empty)
  }

}
