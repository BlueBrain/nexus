package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.defaultS3StorageId
import ch.epfl.bluebrain.nexus.delta.rdf.utils.UriUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.ship.ProjectMapper
import ch.epfl.bluebrain.nexus.ship.resources.DistributionPatcher._
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax.KeyOps

final class DistributionPatcher(fileSelfParser: FileSelf, projectMapper: ProjectMapper, targetBase: BaseUri) {

  /**
    * Distribution may be defined as an object or as an array in original payloads
    */
  def singleOrArray: Json => IO[Json] = root.distribution.json.modifyA { json =>
    json.asArray match {
      case Some(array) => array.traverse(single).map(Json.arr(_: _*))
      case None        => single(json)
    }
  }(_)

  def single: Json => IO[Json] = (json: Json) => fileContentUrl(json).map(toS3Location)

  private def toS3Location: Json => Json = root.atLocation.store.json.replace(targetStorage)

  private def fileContentUrl: Json => IO[Json] = root.contentUrl.string.modifyA { string: String =>
    for {
      uri             <- parseAsUri(string)
      fileSelfAttempt <- fileSelfParser.parse(uri).attempt
      result          <- fileSelfAttempt match {
                           case Right((project, resourceRef)) =>
                             val targetProject = projectMapper.map(project)
                             IO.pure(ResourceUris("files", targetProject, resourceRef.original).accessUri(targetBase).toString())
                           case Left(error)                   =>
                             // We log and keep the value
                             logger.error(error)(s"'$string' could not be parsed as a file self").as(string)
                         }
    } yield result
  }(_)

  private def parseAsUri(string: String) = IO.fromEither(UriUtils.uri(string).leftMap(new IllegalArgumentException(_)))

}

object DistributionPatcher {
  private val logger = Logger[DistributionPatcher]

  // All files are moved to a storage in S3 with a stable id
  private val targetStorage = Json.obj("@id" := defaultS3StorageId, "@type" := "S3Storage", "_rev" := 1)

}
