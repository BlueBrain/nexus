package ch.epfl.bluebrain.nexus.ship.resources

import akka.http.scaladsl.model.{ContentType, Uri}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, MultiPartDigest, NoDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.defaultS3StorageId
import ch.epfl.bluebrain.nexus.delta.rdf.utils.UriUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.ship.resources.DistributionPatcher._
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import io.circe.optics.JsonPath.root
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

final class DistributionPatcher(
    fileSelfParser: FileSelf,
    projectMapper: ProjectMapper,
    iriPatcher: IriPatcher,
    targetBase: BaseUri,
    locationPrefixToStripOpt: Option[Uri],
    fetchFileAttributes: (ProjectRef, ResourceRef) => IO[FileAttributes]
) {

  def patchAll: Json => IO[Json] = patchInRoot(_).flatMap(patchInHasPart)

  private def patchInHasPart: Json => IO[Json] = root.hasPart.json.modifyA { json =>
    json.asArray match {
      case Some(array) => array.parTraverse(patchInRoot).map(Json.arr(_: _*))
      case None        => patchInRoot(json)
    }
  }(_)

  /**
    * Distribution may be defined as an object or as an array in original payloads
    */
  private def patchInRoot: Json => IO[Json] = root.distribution.json.modifyA { json =>
    json.asArray match {
      case Some(array) => array.parTraverse(single).map(Json.arr(_: _*))
      case None        => single(json)
    }
  }(_)

  private def modificationsForFile(originalProject: ProjectRef, resourceRef: ResourceRef): IO[Json => Json] = {
    val targetProject                                = projectMapper.map(originalProject)
    val patchedResourceRef                           = iriPatcher(resourceRef)
    val newContentUrl                                = createContentUrl(targetProject, patchedResourceRef)
    val fileAttributeModifications: IO[Json => Json] =
      fetchFileAttributes(targetProject, patchedResourceRef).attempt.flatMap {
        case Right(attributes) =>
          logger.debug(s"File '$patchedResourceRef' in project '$targetProject' fetched successfully") >>
            IO.pure(
              setLocation(attributes.location.toString())
                .andThen(setContentSize(attributes.bytes))
                .andThen(setEncodingFormat(attributes.mediaType))
                .andThen(setDigest(attributes.digest))
            )
        case Left(e)           =>
          logger.warn(e)(s"File '$patchedResourceRef' in project '$targetProject' could not be fetched") >>
            IO.pure(identity)
      }

    fileAttributeModifications.map(_.andThen(setContentUrl(newContentUrl.toString())))
  }

  private def stripLocationOnUnknownFile(json: Json): Json = {
    locationPrefixToStripOpt.fold(json) { locationPrefixToStrip =>
      root.atLocation.location.string.modify { location =>
        location.replaceFirst(locationPrefixToStrip.toString, "file://")
      }(json)
    }
  }

  private def createContentUrl(project: ProjectRef, resourceRef: ResourceRef): Uri = {
    val withoutVersioning = ResourceUris("files", project, resourceRef.iri).accessUri(targetBase)
    resourceRef match {
      case Latest(_)           => withoutVersioning
      case Revision(_, _, rev) => withoutVersioning.withQuery(Uri.Query("rev" -> rev.toString))
      case Tag(_, _, tag)      => withoutVersioning.withQuery(Uri.Query("tag" -> tag.toString))
    }
  }

  private[resources] def single(json: Json): IO[Json] = extractIds(json)
    .flatMap {
      case Some((project, resource)) => modificationsForFile(project, resource).map(_(json))
      case None                      => IO.pure(stripLocationOnUnknownFile(json))
    }
    .map(toS3Location)

  private def setContentUrl(newContentUrl: String)                = root.contentUrl.string.replace(newContentUrl)
  private def setLocation(newLocation: String)                    = (json: Json) =>
    json.deepMerge(Json.obj("atLocation" := Json.obj("location" := newLocation)))
  private def setContentSize(newSize: Long)                       = (json: Json) =>
    json.deepMerge(Json.obj("contentSize" := Json.obj("unitCode" := "bytes", "value" := newSize)))
  private def setEncodingFormat(contentType: Option[ContentType]) = (json: Json) =>
    contentType.fold(json) { ct =>
      json.deepMerge(Json.obj("encodingFormat" := ct.mediaType.value))
    }

  private def setDigest(digest: Digest) = (json: Json) => json.deepMerge(Json.obj("digest" := digest))

  private def toS3Location: Json => Json = root.atLocation.store.json.replace(targetStorage)

  private def extractIds(json: Json): IO[Option[(ProjectRef, ResourceRef)]] = {
    root.contentUrl.string.getOption(json).flatTraverse { contentUrl =>
      for {
        uri <- parseAsUri(contentUrl)
        ids <- parseFileSelf(uri)
      } yield {
        ids
      }
    }
  }

  private def parseFileSelf(uri: Uri): IO[Option[(ProjectRef, ResourceRef)]] = {
    fileSelfParser.parse(uri).attempt.flatMap {
      case Right((projectRef, resourceRef)) =>
        IO.pure(Some((projectRef, resourceRef)))
      case Left(error)                      =>
        logger.error(error)(s"'$uri' could not be parsed as a file self").as(None)
    }
  }

  private def parseAsUri(string: String): IO[Uri] =
    IO.fromEither(UriUtils.uri(string).leftMap(new IllegalArgumentException(_)))

  implicit private val digestEncoder: Encoder.AsObject[Digest] = Encoder.encodeJsonObject.contramapObject {
    case ComputedDigest(algorithm, value)                 => JsonObject("algorithm" := algorithm, "value" := value)
    case MultiPartDigest(algorithm, value, numberOfParts) =>
      JsonObject("algorithm" := algorithm, "value" := value, "numberOfParts" := numberOfParts)
    case NotComputedDigest                                => JsonObject("value" := "")
    case NoDigest                                         => JsonObject("value" := "")
  }

}

object DistributionPatcher {
  private val logger = Logger[DistributionPatcher]

  // All files are moved to a storage in S3 with a stable id
  private val targetStorage = Json.obj("@id" := defaultS3StorageId, "@type" := "S3Storage", "_rev" := 1)

}
